package com.crosshub.backoffice.booking;

import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * v1 "Booking" 테이블 매핑 (읽기 중심).
 * partnerId는 예약 시점 스냅샷(업체 범위 필터용). productId와 함께 Product/Partner를 읽기전용 조인으로 붙인다.
 *
 * enum(status/confirmType/customerDecision)은 native enum → String 매핑(+ stringtype=unspecified).
 * priceBreakdown은 jsonb → 원본 JSON 문자열.
 */
@Entity
@Table(name = "Booking")
public class Booking {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "productId")
    private String productId;

    @Column(name = "partnerId")
    private String partnerId;

    // 읽기전용 조인 (이름/통화 조회용). 쓰기는 scalar id 컬럼으로만.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "productId", insertable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partnerId", insertable = false, updatable = false)
    private Partner partner;

    @Column(name = "customerName")
    private String customerName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "nationality")
    private String nationality;

    @Column(name = "useDate")
    private LocalDate useDate;

    /** 예약 시점에 박힌 연맹 몫(%p)·PG 수수료율(%) — 정산 배분 계산용 (TP 앱 예약 생성 때 채워짐) */
    @Column(name = "affiliateShareRate")
    private int affiliateShareRate;

    @Column(name = "pgFeeRate")
    private double pgFeeRate;

    @Column(name = "people")
    private int people;

    @Column(name = "amount")
    private int amount;

    @Column(name = "commissionRate")
    private int commissionRate;

    @Column(name = "commission")
    private int commission;

    @Column(name = "net")
    private int net;

    @Column(name = "cancelFee")
    private Integer cancelFee;

    @Column(name = "cancelReason")
    private String cancelReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "priceBreakdown")
    private String priceBreakdown;

    @Column(name = "status")
    private String status;

    @Column(name = "confirmType")
    private String confirmType;

    @Column(name = "requestedAt")
    private OffsetDateTime requestedAt;

    @Column(name = "quoteAmount")
    private Integer quoteAmount;

    @Column(name = "quoteValidUntil")
    private LocalDate quoteValidUntil;

    @Column(name = "quoteMessage")
    private String quoteMessage;

    @Column(name = "customerDecision")
    private String customerDecision;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    @Column(name = "updatedAt")
    private OffsetDateTime updatedAt;

    /** 고객 토큰의 sub (idblock realm). 고객용 API 로 만든 예약만 채워진다. 옛 행은 null */
    @Column(name = "customerId")
    private String customerId;

    /** 앱이 신청마다 보내는 멱등키 — 재시도·중복 탭으로 같은 요청이 두 번 오면 기존 예약을 돌려준다 */
    @Column(name = "idempotencyKey")
    private String idempotencyKey;

    /**
     * 고객 신청으로 새 예약. FE 의 옛 createBookingFromApp 과 같은 스냅샷 규칙:
     * 신청 시점의 업체 수수료율·연맹 몫·PG 수수료율·확정방식을 박아 두고, 나중에 요율이 바뀌어도 이 예약은 그대로.
     */
    public static Booking newFromCustomer(String productId, String partnerId, String customerId, String customerName,
                                          String phone, String email, String nationality, LocalDate useDate, int people,
                                          int amount, int commissionRate, Fees.Split split, int affiliateShareRate,
                                          double pgFeeRate, String priceBreakdownJson, String confirmType, String status,
                                          String idempotencyKey) {
        Booking b = new Booking();
        b.id = java.util.UUID.randomUUID().toString().replace("-", "");
        b.productId = productId;
        b.partnerId = partnerId;
        b.customerId = customerId;
        b.customerName = customerName;
        b.phone = phone;
        b.email = email;
        b.nationality = nationality;
        b.useDate = useDate;
        b.people = people;
        b.amount = amount;
        b.commissionRate = commissionRate;
        b.commission = split.commission();
        b.net = split.net();
        b.affiliateShareRate = affiliateShareRate;
        b.pgFeeRate = pgFeeRate;
        b.priceBreakdown = priceBreakdownJson;
        b.confirmType = confirmType;
        b.status = status;
        b.idempotencyKey = idempotencyKey;
        OffsetDateTime now = OffsetDateTime.now();
        b.requestedAt = now;
        b.createdAt = now;
        b.updatedAt = now;
        return b;
    }

    protected Booking() {
    }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getProductId() { return productId; }
    public int getAffiliateShareRate() { return affiliateShareRate; }
    public double getPgFeeRate() { return pgFeeRate; }
    public String getPartnerId() { return partnerId; }
    public Product getProduct() { return product; }
    public Partner getPartner() { return partner; }
    public String getCustomerName() { return customerName; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getNationality() { return nationality; }
    public LocalDate getUseDate() { return useDate; }
    public int getPeople() { return people; }
    public int getAmount() { return amount; }
    public void setAmount(int v) { this.amount = v; }
    public int getCommissionRate() { return commissionRate; }
    public void setCommissionRate(int v) { this.commissionRate = v; }
    public int getCommission() { return commission; }
    public void setCommission(int v) { this.commission = v; }
    public int getNet() { return net; }
    public void setNet(int v) { this.net = v; }
    public Integer getCancelFee() { return cancelFee; }
    public void setCancelFee(Integer v) { this.cancelFee = v; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String v) { this.cancelReason = v; }
    public String getPriceBreakdown() { return priceBreakdown; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getConfirmType() { return confirmType; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public Integer getQuoteAmount() { return quoteAmount; }
    public void setQuoteAmount(Integer v) { this.quoteAmount = v; }
    public LocalDate getQuoteValidUntil() { return quoteValidUntil; }
    public String getQuoteMessage() { return quoteMessage; }
    public void setQuoteMessage(String v) { this.quoteMessage = v; }
    public String getCustomerDecision() { return customerDecision; }
    public void setCustomerDecision(String v) { this.customerDecision = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
