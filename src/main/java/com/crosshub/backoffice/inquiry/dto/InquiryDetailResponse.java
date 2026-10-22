package com.crosshub.backoffice.inquiry.dto;

import com.crosshub.backoffice.inquiry.Inquiry;
import java.time.OffsetDateTime;
import java.util.List;

/** 상세 (대화 스레드 포함). */
public record InquiryDetailResponse(
        String id,
        String productId,
        String productName,
        String customerName,
        String language,
        String question,
        String questionTranslated,
        String questionTranslatedLocale,
        String answer,
        String answerTranslated,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<InquiryMessageResponse> messages) {

    public static InquiryDetailResponse from(Inquiry i) {
        return new InquiryDetailResponse(
                i.getId(), i.getProductId(),
                i.getProduct() != null ? i.getProduct().getName() : null,
                i.getCustomerName(), i.getLanguage(), i.getQuestion(), i.getQuestionTranslated(),
                i.getQuestionTranslatedLocale(), i.getAnswer(), i.getAnswerTranslated(),
                i.getStatus(), i.getCreatedAt(), i.getUpdatedAt(),
                i.getMessages().stream().map(InquiryMessageResponse::from).toList());
    }
}
