package com.crosshub.backoffice.inquiry.dto;

import com.crosshub.backoffice.inquiry.InquiryMessage;
import java.time.OffsetDateTime;

public record InquiryMessageResponse(
        String id,
        String sender,
        String text,
        String textTranslated,
        String translatedLocale,
        String textLocale,
        OffsetDateTime createdAt) {

    public static InquiryMessageResponse from(InquiryMessage m) {
        return new InquiryMessageResponse(
                m.getId(), m.getSender(), m.getText(), m.getTextTranslated(),
                m.getTranslatedLocale(), m.getTextLocale(), m.getCreatedAt());
    }
}
