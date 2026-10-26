package com.crosshub.backoffice.inquiry;

import com.crosshub.backoffice.account.AuditLog;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.common.PageResponse;
import com.crosshub.backoffice.inquiry.dto.InquiryDetailResponse;
import com.crosshub.backoffice.inquiry.dto.InquirySummaryResponse;
import com.crosshub.backoffice.inquiry.dto.PostAnswerRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InquiryService {

    private static final int PAGE_SIZE = 20;

    private final InquiryRepository repository;
    private final InquiryMessageRepository messages;
    private final AuditLogRepository auditLogs;

    public InquiryService(InquiryRepository repository, InquiryMessageRepository messages, AuditLogRepository auditLogs) {
        this.repository = repository;
        this.messages = messages;
        this.auditLogs = auditLogs;
    }

    /** 목록. partner는 자기 업체(product.partnerId)만. 오래 기다린 순(접수 오래된 순). */
    @Transactional(readOnly = true)
    public PageResponse<InquirySummaryResponse> list(Caller caller, String status, String keyword, int page) {
        boolean allPartners = caller.isCrosshub();
        List<String> partnerIds = allPartners ? List.of("_none_") : caller.partnerIdsForQuery();

        boolean anyStatus = (status == null || status.isBlank());
        // status는 Postgres enum이라 더미도 유효한 enum 값이어야 한다("pending"). anyStatus=true면 안 쓰임.
        String st = anyStatus ? "pending" : status;

        boolean noKeyword = (keyword == null || keyword.isBlank());
        String kw = noKeyword ? "_none_" : "%" + keyword.trim().toLowerCase() + "%";

        Pageable pageable = PageRequest.of(
                Math.max(0, page - 1), PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<Inquiry> result = repository.search(allPartners, partnerIds, anyStatus, st, noKeyword, kw, pageable);
        List<InquirySummaryResponse> rows = result.getContent().stream()
                .map(InquirySummaryResponse::from)
                .toList();
        return PageResponse.of(result, rows);
    }

    /** 상세(스레드 포함). 소유 업체만. */
    @Transactional(readOnly = true)
    public InquiryDetailResponse get(Caller caller, String id) {
        Inquiry i = repository.findById(id).orElseThrow(() -> notFound(id));
        String ownerPartnerId = i.getProduct() != null ? i.getProduct().getPartnerId() : null;
        if (!caller.canAccessPartner(ownerPartnerId)) {
            throw notFound(id);
        }
        return InquiryDetailResponse.from(i);   // messages는 트랜잭션 안에서 로딩
    }


    /**
     * 관리자 답변 등록. 소유 업체만. 같은 답변이 그대로 다시 들어오면(더블클릭·재시도) 중복 저장하지 않고
     * 그대로 성공 처리한다.
     */
    @Transactional
    public InquiryDetailResponse postAnswer(Caller caller, String id, PostAnswerRequest req) {
        Inquiry i = repository.findById(id).orElseThrow(() -> notFound(id));
        String ownerPartnerId = i.getProduct() != null ? i.getProduct().getPartnerId() : null;
        if (!caller.canAccessPartner(ownerPartnerId)) throw notFound(id);
        // 빈 답변·지나치게 긴 답변은 저장하지 않는다 ({code}는 ApiExceptionHandler가 내보냄)
        if (req.text() == null || req.text().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "required");
        if (req.text().length() > ANSWER_MAX || (req.textTranslated() != null && req.textTranslated().length() > ANSWER_MAX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tooLong");
        }

        String textTranslated = (req.textTranslated() == null || req.textTranslated().isBlank()) ? null : req.textTranslated();
        if (Objects.equals(i.getAnswer(), req.text()) && Objects.equals(i.getAnswerTranslated(), textTranslated)) {
            return InquiryDetailResponse.from(i);
        }

        // saveAndFlush로 즉시 insert해 둬야, 아래 InquiryDetailResponse.from(i)가 i.getMessages()를
        // (아직 로딩 전이라) 새로 SELECT할 때 이 메시지가 바로 보인다. save()만 하면 flush가 미뤄져서
        // 지연 로딩 조회가 이 트랜잭션 안에서도 방금 쓴 행을 못 보는 경우가 있다(Product 생성 때의 partnerName null과 같은 종류).
        messages.saveAndFlush(InquiryMessage.of(id, "admin", req.text(), textTranslated, req.locale()));
        i.setAnswer(req.text());
        i.setAnswerTranslated(textTranslated);
        i.setStatus("answered");
        i.setUpdatedAt(OffsetDateTime.now());
        auditLogs.save(AuditLog.of(caller.userId(), "inquiry_answer", ownerPartnerId, id, i.getCustomerName()));

        return InquiryDetailResponse.from(i);
    }

    /** 답변 원문·번역문 최대 길이 */
    static final int ANSWER_MAX = 5000;

    /**
     * 고객·관리자 메시지의 번역 결과 저장. 화면의 "원문 자동번역 생성" 버튼이 받아온 결과를 영구 저장해서, 다시 조회해도 재번역하지 않게 한다.
     * locale 은 번역한 관리자의 화면 언어 — 다른 언어 화면에선 이 번역문 대신 원문을 보여주고 다시 번역하게 한다.
     * 고객 메시지는 Inquiry.questionTranslated 로도 중복 저장돼 있어서(목록 화면이 그 값을 봄) 같이 갱신한다.
     * admin 메시지는 여러 건일 수 있어서, Inquiry.answerTranslated 는 가장 최근 답변의 번역일 때만 같이 갱신한다.
     */
    @Transactional
    public void saveMessageTranslation(Caller caller, String messageId, String textTranslated, String locale) {
        InquiryMessage m = messages.findById(messageId).orElseThrow(() -> notFound(messageId));
        Inquiry i = repository.findById(m.getInquiryId()).orElseThrow(() -> notFound(messageId));
        String ownerPartnerId = i.getProduct() != null ? i.getProduct().getPartnerId() : null;
        if (!caller.canAccessPartner(ownerPartnerId)) throw notFound(messageId);
        if (textTranslated == null || textTranslated.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "required");
        if (textTranslated.length() > ANSWER_MAX) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tooLong");
        if (locale == null || locale.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "required");

        boolean customer = "customer".equals(m.getSender());
        m.setTranslation(textTranslated, customer ? locale : null);
        if (customer) {
            // 문의당 고객 메시지는 항상 1건(최초 질문)이라 바로 반영해도 된다
            i.setQuestionTranslated(textTranslated, locale);
        } else {
            boolean latest = messages.findFirstByInquiryIdAndSenderOrderByCreatedAtDesc(i.getId(), "admin")
                    .map(x -> x.getId().equals(m.getId())).orElse(false);
            if (latest) i.setAnswerTranslated(textTranslated);
        }
        auditLogs.save(AuditLog.of(caller.userId(), "inquiry_translation", ownerPartnerId, i.getId(), i.getCustomerName()));
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다: " + id);
    }
}
