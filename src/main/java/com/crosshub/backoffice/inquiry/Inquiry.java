package com.crosshub.backoffice.inquiry;

import com.crosshub.backoffice.product.Product;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * v1 "Inquiry" 테이블 매핑. 문의엔 partnerId 컬럼이 없어 업체 범위는 product.partnerId로 건다.
 * 메시지 스레드(InquiryMessage)는 createdAt 순으로 딸려 온다.
 */
@Entity
@Table(name = "Inquiry")
public class Inquiry {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "productId")
    private String productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "productId", insertable = false, updatable = false)
    private Product product;

    @Column(name = "customerName")
    private String customerName;

    @Column(name = "language")
    private String language;         // en | zh | ja | vi

    @Column(name = "question")
    private String question;

    @Column(name = "questionTranslated")
    private String questionTranslated;

    @Column(name = "questionTranslatedLocale")
    private String questionTranslatedLocale;

    @Column(name = "answer")
    private String answer;

    @Column(name = "answerTranslated")
    private String answerTranslated;

    @Column(name = "status")
    private String status;           // pending | answered

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    @Column(name = "updatedAt")
    private OffsetDateTime updatedAt;

    /** 고객 토큰의 sub (idblock realm). 고객용 API 로 만든 문의만 채워진다. 옛 행은 null */
    @Column(name = "customerId")
    private String customerId;

    @Column(name = "idempotencyKey")
    private String idempotencyKey;

    /** 고객 접수로 새 문의 + 첫 메시지(customer). 번역은 관리자가 상세를 열 때 하므로 여기선 안 한다 (FE 옛 createInquiryFromApp 과 동일) */
    public static Inquiry newFromCustomer(String productId, String customerId, String customerName, String language,
                                          String question, String idempotencyKey) {
        Inquiry i = new Inquiry();
        i.id = java.util.UUID.randomUUID().toString().replace("-", "");
        i.productId = productId;
        i.customerId = customerId;
        i.customerName = customerName;
        i.language = language;
        i.question = question;
        i.questionTranslated = "";
        i.status = "pending";
        i.idempotencyKey = idempotencyKey;
        OffsetDateTime now = OffsetDateTime.now();
        i.createdAt = now;
        i.updatedAt = now;
        i.messages.add(InquiryMessage.of(i.id, "customer", question, null, null));
        return i;
    }

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = false)
    @JoinColumn(name = "inquiryId", insertable = false, updatable = false)
    @OrderBy("createdAt asc")
    private List<InquiryMessage> messages = new ArrayList<>();

    protected Inquiry() {
    }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getProductId() { return productId; }
    public Product getProduct() { return product; }
    public String getCustomerName() { return customerName; }
    public String getLanguage() { return language; }
    public String getQuestion() { return question; }
    public void setQuestionTranslated(String text, String locale) {
        this.questionTranslated = text;
        this.questionTranslatedLocale = locale;
    }
    public String getQuestionTranslated() { return questionTranslated; }
    public String getQuestionTranslatedLocale() { return questionTranslatedLocale; }
    public String getAnswer() { return answer; }
    public void setAnswer(String v) { this.answer = v; }
    public String getAnswerTranslated() { return answerTranslated; }
    public void setAnswerTranslated(String v) { this.answerTranslated = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public List<InquiryMessage> getMessages() { return messages; }
}
