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
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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

    /** 고객 토큰의 sub (tp realm). 고객용 API 로 만든 예약만 채워진다. 옛 행은 null */
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
    public int getCommissionRate() { return commissionRate; }
    public void setCommissionRate(int v) { this.commissionRate = v; }
    public int getCommission() { return commission; }
    public int getNet() { return net; }
    public Integer getCancelFee() { return cancelFee; }
    public String getCancelReason() { return cancelReason; }
    public String getPriceBreakdown() { return priceBreakdown; }
    public String getStatus() { return status; }
    public String getConfirmType() { return confirmType; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public Integer getQuoteAmount() { return quoteAmount; }
    public LocalDate getQuoteValidUntil() { return quoteValidUntil; }
    public String getQuoteMessage() { return quoteMessage; }
    public String getCustomerDecision() { return customerDecision; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // ── 상태 전이 규칙 ─────────────────────────────────────────────
    // 예약 상태는 아래 메서드로만 바뀐다 (status setter 없음). 규칙이 한 곳에 있어서
    // 새 쓰기 경로가 생겨도 검사를 빠뜨릴 수 없다. 권한(업체 범위)은 서비스가 먼저 확인한다.

    /** 견적 메시지·취소 사유 길이 상한 */
    public static final int TEXT_MAX = 2000;

    /** changeStatus로 바꿀 수 있는 전이. 견적·수락·환불은 각자 메서드로만.
     *  화면 버튼이 허용하는 전환과 1:1 (bookings-view.tsx의 버튼 노출 조건) */
    private static final Map<String, List<String>> ALLOWED_TRANSITIONS = Map.of(
            "pending", List.of("rejected"),
            "quoted", List.of(),
            "confirmed", List.of("completed", "canceled"),
            "rejected", List.of(),
            "completed", List.of(),
            "canceled", List.of(),
            "refunded", List.of(),
            "withdrawn", List.of());

    /** 업체가 견적을 보낸다. 업체확인중일 때만 */
    public void sendQuote(int quoteAmount, String message) {
        if (!"pending".equals(status)) throw notFound();
        if (quoteAmount <= 0) throw new BookingException(HttpStatus.BAD_REQUEST, "invalidAmount");
        if (tooLong(message)) throw new BookingException(HttpStatus.BAD_REQUEST, "tooLong");
        this.status = "quoted";
        this.quoteAmount = quoteAmount;
        this.quoteMessage = blankToNull(message);
        this.customerDecision = "waiting";
        touch();
    }

    /**
     * 고객이 직접 취소한다 (고객용 API). 업체확인중은 결제 전이라 철회(withdrawn), 확정은 취소표대로 수수료를 떼고 canceled.
     * 고객확인중(quoted)은 "취소"가 아니라 견적 거절(respondToQuote)로 처리하고, 그 외 상태는 409.
     */
    public void cancelByCustomer(IntSupplier cancelFeeNow) {
        switch (status) {
            case "pending" -> this.status = "withdrawn";
            case "confirmed" -> {
                this.status = "canceled";
                this.cancelFee = cancelFeeNow.getAsInt();
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "notCancelable");
        }
        touch();
    }

    /**
     * 고객이 견적에 응답한다. 고객확인중일 때만.
     * 수락하면 견적 금액으로 판매가·수수료·정산액을 다시 계산하고 true(결제 행을 남겨야 함)를 돌려준다.
     */
    public boolean respondToQuote(String decision) {
        if (!"quoted".equals(status)) throw notFound();
        if (!"accepted".equals(decision) && !"declined".equals(decision)) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "invalidDecision");
        }
        if ("accepted".equals(decision)) {
            int paid = quoteAmount != null ? quoteAmount : amount;
            Fees.Split split = Fees.splitAmount(paid, commissionRate);
            this.status = "confirmed";
            this.customerDecision = "accepted";
            this.amount = paid;
            this.commission = split.commission();
            this.net = split.net();
        } else {
            // 고객이 견적을 거절 = 결제 전에 고객이 물러남 → 신청취소 (업체 거절과 구분)
            this.status = "withdrawn";
            this.customerDecision = "declined";
        }
        touch();
        return "accepted".equals(decision);
    }

    /**
     * 견적 거절·이용완료·예약 취소. 허용된 전이가 아니면 404.
     * 취소 수수료는 돈을 받은 예약(확정)만 붙는다 — 계산이 상품 취소표를 읽어야 해서 필요할 때만 부른다.
     */
    public void changeStatus(String to, String reason, IntSupplier cancelFeeByRule) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(status, List.of()).contains(to)) throw notFound();
        if (tooLong(reason)) throw new BookingException(HttpStatus.BAD_REQUEST, "tooLong");
        if ("canceled".equals(to)) {
            this.cancelFee = "confirmed".equals(status) ? cancelFeeByRule.getAsInt() : 0;
            this.cancelReason = blankToNull(reason);
        } else if ("rejected".equals(to)) {
            this.cancelReason = blankToNull(reason);
        }
        this.status = to;
        touch();
    }

    /**
     * 환불 완료. 취소됨일 때만. 수수료는 취소 때 계산해 둔 값 이하로만(깎아주기만) 바꿀 수 있다.
     * 받은 돈(paid)에서 수수료를 뺀 환불액을 돌려준다 (0 이하면 환불 행 없음).
     */
    public int refund(Integer newCancelFee, int paid) {
        if (!"canceled".equals(status)) throw notFound();
        // 값이 빠지면 0원(전액 면제)으로 처리되지 않도록 null을 막는다
        if (newCancelFee == null) throw new BookingException(HttpStatus.BAD_REQUEST, "invalidAmount");
        int ruleFee = cancelFee != null ? cancelFee : 0;
        if (newCancelFee < 0 || newCancelFee > ruleFee) throw new BookingException(HttpStatus.BAD_REQUEST, "invalidAmount");
        this.status = "refunded";
        this.cancelFee = newCancelFee;
        touch();
        return paid - newCancelFee;
    }

    private void touch() { this.updatedAt = OffsetDateTime.now(); }

    private static boolean tooLong(String text) { return text != null && text.length() > TEXT_MAX; }

    private static String blankToNull(String text) { return (text == null || text.isBlank()) ? null : text; }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다: " + id);
    }
}
