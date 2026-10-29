package com.crosshub.backoffice.product;

import com.crosshub.backoffice.partner.Partner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * v1(Prisma)이 만든 "Product" 테이블에 매핑되는 JPA 엔티티.
 * 스키마는 Prisma 마이그레이션이 단일 진실이므로 BE는 ddl-auto=none 으로 읽기/쓰기만 한다.
 *
 * 주의: Prisma의 @updatedAt/@default는 DB 트리거가 아니라 Prisma 클라이언트가 채우던 값이다.
 * BE에서 직접 insert/update할 때는 createdAt/updatedAt을 코드로 채운다 (ProductService).
 *
 * - enum 컬럼(status/confirmType/category)은 Postgres native enum이지만 'package'가 Java 예약어라
 *   String 매핑 + JDBC stringtype=unspecified 로 호환시킨다.
 * - jsonb 컬럼(schedule/cancelRules/priceTable/blocks)은 원본 JSON 문자열 그대로 보관한다.
 * - images는 text[] → String[].
 */
@Entity
@Table(name = "Product")
public class Product {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "partnerId")
    private String partnerId;

    // 읽기전용 조인 (업체명 조회용, 요약 응답에서 씀). 쓰기는 scalar partnerId 컬럼으로만.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partnerId", insertable = false, updatable = false)
    private Partner partner;

    @Column(name = "status")
    private String status;

    @Column(name = "hiddenByPartnerOff")
    private boolean hiddenByPartnerOff;

    @Column(name = "reviewRejectReason")
    private String reviewRejectReason;

    @Column(name = "confirmType")
    private String confirmType;

    @Column(name = "category")
    private String category;

    @Column(name = "name")
    private String name;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "images")
    private String[] images;

    @Column(name = "description")
    private String description;

    @Column(name = "sourceLocale")
    private String sourceLocale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schedule")
    private String schedule;

    @Column(name = "saleStart")
    private LocalDate saleStart;

    @Column(name = "saleEnd")
    private LocalDate saleEnd;

    @Column(name = "deadlineDays")
    private int deadlineDays;

    @Column(name = "deadlineTime")
    private String deadlineTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cancelRules")
    private String cancelRules;

    @Column(name = "priceFrom")
    private Integer priceFrom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "priceTable")
    private String priceTable;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blocks")
    private String blocks;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    @Column(name = "updatedAt")
    private OffsetDateTime updatedAt;

    public Product() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPartnerId() { return partnerId; }
    public void setPartnerId(String partnerId) { this.partnerId = partnerId; }

    public Partner getPartner() { return partner; }

    public String getStatus() { return status; }

    public boolean isHiddenByPartnerOff() { return hiddenByPartnerOff; }

    public String getReviewRejectReason() { return reviewRejectReason; }

    public String getConfirmType() { return confirmType; }
    public void setConfirmType(String v) { this.confirmType = v; }

    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String[] getImages() { return images; }
    public void setImages(String[] images) { this.images = images; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSourceLocale() { return sourceLocale; }
    public void setSourceLocale(String v) { this.sourceLocale = v; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public LocalDate getSaleStart() { return saleStart; }
    public void setSaleStart(LocalDate v) { this.saleStart = v; }

    public LocalDate getSaleEnd() { return saleEnd; }
    public void setSaleEnd(LocalDate v) { this.saleEnd = v; }

    public int getDeadlineDays() { return deadlineDays; }
    public void setDeadlineDays(int v) { this.deadlineDays = v; }

    public String getDeadlineTime() { return deadlineTime; }
    public void setDeadlineTime(String v) { this.deadlineTime = v; }

    public String getCancelRules() { return cancelRules; }
    public void setCancelRules(String v) { this.cancelRules = v; }

    public Integer getPriceFrom() { return priceFrom; }
    public void setPriceFrom(Integer v) { this.priceFrom = v; }

    public String getPriceTable() { return priceTable; }
    public void setPriceTable(String v) { this.priceTable = v; }

    public String getBlocks() { return blocks; }
    public void setBlocks(String v) { this.blocks = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }

    // ── 상태 전이 규칙 ─────────────────────────────────────────────
    // 상품 상태는 아래 메서드로만 바뀐다 (status setter 없음). 규칙이 한 곳에 있어서
    // 새 쓰기 경로가 생겨도 검사를 빠뜨릴 수 없다. 업체 범위(남의 업체면 404)는 서비스가 먼저 확인한다.
    //   임시저장(draft) → 심사중(review) → 노출중(live) ↔ 숨김(hidden)

    public static final Set<String> STATUSES = Set.of("draft", "review", "live", "hidden");

    /** 반려 사유 길이 상한 */
    public static final int REASON_MAX = 1000;

    /** 새 상품의 첫 상태. 임시저장이 아니면 등록 직후는 항상 심사중 */
    public void initStatus(boolean draft) {
        this.status = draft ? "draft" : "review";
        this.hiddenByPartnerOff = false;
    }

    /**
     * 변경요청 없이 바로 수정·삭제·상태 변경할 수 있는가.
     * 업체·연맹은 임시저장·심사중만. 노출중·숨김은 변경요청 → 크로스허브 승인으로만 바뀐다.
     */
    public void requireDirectEditable(boolean byCrosshub) {
        if (byCrosshub) return;
        if (!"draft".equals(status) && !"review".equals(status)) {
            throw new ProductException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    /** 수정 저장 시 submit이면 임시저장 → 심사중. 올렸으면 true */
    public boolean submitIfDraft(boolean submit) {
        if (!submit || !"draft".equals(status)) return false;
        this.status = "review";
        return true;
    }

    /**
     * 상태 전환(승인·반려·노출·숨김). 바뀌었으면 true, 같은 상태면 false.
     * - 심사중에서 나가는 것(승인·반려)은 크로스허브만. 심사중 → 숨김 → 노출중으로 돌아 승인을 건너뛰지 못하게
     * - 업체·연맹은 노출중·숨김 상품을 바로 못 바꾼다(변경요청으로) — 반려된 상품을 바로 노출하는 것 방지
     * - 심사중 → 숨김(반려)은 사유 필수, 노출중으로 가면 업체가 활성이고 수수료율이 있어야 함
     * @param partner 이 상품의 업체 (노출중으로 갈 때만 필요, 없으면 null)
     */
    public boolean changeStatus(boolean byCrosshub, String to, String reason, com.crosshub.backoffice.partner.Partner partner) {
        return changeStatus(byCrosshub, to, reason, partner, () -> true);
    }

    /**
     * @param translationsComplete 노출중으로 갈 때만 묻는다 — 원문 언어를 뺀 모든 언어에 번역이 다 채워졌는지.
     *                             아니면 translationMissing (TP 앱의 외국인 고객에게 원문만 나가지 않게)
     */
    public boolean changeStatus(boolean byCrosshub, String to, String reason, com.crosshub.backoffice.partner.Partner partner,
                                java.util.function.BooleanSupplier translationsComplete) {
        String from = status;
        // 모르는 값이면 enum 캐스팅에서 500이 나니 먼저 막는다
        if (to == null || !STATUSES.contains(to)) throw notFound();
        if ("draft".equals(from) || "draft".equals(to)) throw notFound();
        if ("review".equals(from) && !"review".equals(to) && !byCrosshub) {
            throw new ProductException(HttpStatus.FORBIDDEN, "approveCrosshubOnly");
        }
        requireDirectEditable(byCrosshub);
        if ("review".equals(to) && !"review".equals(from)) throw notFound(); // 심사중으로 되돌리기는 없음
        boolean rejecting = "review".equals(from) && "hidden".equals(to);
        if (rejecting && (reason == null || reason.isBlank())) {
            throw new ProductException(HttpStatus.BAD_REQUEST, "required");
        }
        if (reason != null && reason.length() > REASON_MAX) {
            throw new ProductException(HttpStatus.BAD_REQUEST, "tooLong");
        }
        if (from.equals(to)) return false;

        if ("live".equals(to)) {
            if (!translationsComplete.getAsBoolean()) throw new ProductException(HttpStatus.CONFLICT, "translationMissing");
            if (partner == null || !partner.isActive()) throw new ProductException(HttpStatus.CONFLICT, "partnerInactive");
            if (partner.getCommissionRate() == null) throw new ProductException(HttpStatus.CONFLICT, "commissionMissing");
        }
        // 반려 사유: 반려(review→hidden)일 때만 저장, 노출(재승인)되면 지난 사유를 지운다. 그 외는 그대로
        if (rejecting) {
            this.reviewRejectReason = reason.trim();
        } else if ("live".equals(to)) {
            this.reviewRejectReason = null;
        }
        this.status = to;
        this.updatedAt = OffsetDateTime.now();
        return true;
    }

    /** 업체 비활성화: 노출중 상품을 숨기고 "업체가 꺼서 숨김" 표시를 남긴다 */
    public void hideForPartnerOff() {
        this.status = "hidden";
        this.hiddenByPartnerOff = true;
    }

    /** 업체 재활성화 + 되돌리기: 업체가 꺼서 숨겨졌던 상품을 다시 노출 */
    public void restoreAfterPartnerOn() {
        this.status = "live";
        this.hiddenByPartnerOff = false;
    }

    /** 업체 재활성화(되돌리지 않음): 표시만 지운다. 그 뒤로는 업체가 직접 숨긴 상품과 같다 */
    public void clearPartnerOffMark() {
        this.hiddenByPartnerOff = false;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다: " + id);
    }
}
