package com.crosshub.backoffice.inquiry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    public static InquiryMessage of(String inquiryId, String sender, String text, String textTranslated, String textLocale) {
        InquiryMessage m = new InquiryMessage();
        m.id = UUID.randomUUID().toString().replace("-", "");
        m.inquiryId = inquiryId;
        m.sender = sender;
        m.text = text;
        m.textTranslated = textTranslated;
        m.textLocale = textLocale;
        m.createdAt = OffsetDateTime.now();
        return m;
    }

    /** 번역 결과 저장. 고객 메시지는 번역한 관리자의 화면 언어를 같이 남기고, 관리자 답변 번역은 고객 언어라 locale 을 안 남긴다 */
    public void setTranslation(String textTranslated, String translatedLocale) {
        this.textTranslated = textTranslated;
        this.translatedLocale = translatedLocale;
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
