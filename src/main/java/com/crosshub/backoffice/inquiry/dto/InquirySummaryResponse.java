package com.crosshub.backoffice.inquiry.dto;

import com.crosshub.backoffice.inquiry.Inquiry;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 목록 요약. FE 목록 화면이 목록·상세를 한 화면(모달)에서 같이 써서 messages(대화 스레드)까지 담는다 —
 * InquiryService.get()이 이미 트랜잭션 안에서 messages를 로딩하는 것과 같은 패턴을 list()에도 쓴다.
 * partnerId는 Inquiry에 컬럼이 없어 product.partnerId로 (Product에 Partner 조인이 있어 접근 가능).
 */
public record InquirySummaryResponse(
        String id,
        String productId,
        String productName,
        String partnerId,
        String partnerName,
        String customerName,
        String language,
        String question,
        String questionTranslated,
        String questionTranslatedLocale,
        String status,
        OffsetDateTime createdAt,
        List<InquiryMessageResponse> messages) {

    public static InquirySummaryResponse from(Inquiry i) {
        return new InquirySummaryResponse(
                i.getId(), i.getProductId(),
                i.getProduct() != null ? i.getProduct().getName() : null,
                i.getProduct() != null ? i.getProduct().getPartnerId() : null,
                i.getProduct() != null && i.getProduct().getPartner() != null ? i.getProduct().getPartner().getName() : null,
                i.getCustomerName(), i.getLanguage(), i.getQuestion(), i.getQuestionTranslated(),
                i.getQuestionTranslatedLocale(), i.getStatus(), i.getCreatedAt(),
                i.getMessages().stream().map(InquiryMessageResponse::from).toList());
    }
}
