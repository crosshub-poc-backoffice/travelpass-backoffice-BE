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

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다: " + id);
    }
}
