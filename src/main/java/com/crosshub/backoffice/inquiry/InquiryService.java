package com.crosshub.backoffice.inquiry;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.common.PageResponse;
import com.crosshub.backoffice.inquiry.dto.InquiryDetailResponse;
import com.crosshub.backoffice.inquiry.dto.InquirySummaryResponse;
import java.util.List;
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

    public InquiryService(InquiryRepository repository) {
        this.repository = repository;
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


    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다: " + id);
    }
}
