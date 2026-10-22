package com.crosshub.backoffice.inquiry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** v1 "InquiryMessage" — 문의 대화 1건. */
@Entity
@Table(name = "InquiryMessage")
public class InquiryMessage {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "inquiryId")
    private String inquiryId;

    @Column(name = "sender")
    private String sender;           // customer | admin (native enum → String)

    @Column(name = "text")
    private String text;

    @Column(name = "textTranslated")
    private String textTranslated;

    @Column(name = "translatedLocale")
    private String translatedLocale;

    @Column(name = "textLocale")
    private String textLocale;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    protected InquiryMessage() {
    }

    public String getId() { return id; }
    public String getInquiryId() { return inquiryId; }
    public String getSender() { return sender; }
    public String getText() { return text; }
    public String getTextTranslated() { return textTranslated; }
    public String getTranslatedLocale() { return translatedLocale; }
    public String getTextLocale() { return textLocale; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
