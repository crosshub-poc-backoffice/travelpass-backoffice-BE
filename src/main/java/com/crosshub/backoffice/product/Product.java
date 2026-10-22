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

    // 읽기전용 조인 (업체명 조회용, 요약 응답에서 씀). 쓰기는 scalar partnerId 컬럼으로만 (Booking과 동일 패턴).
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
    public void setStatus(String status) { this.status = status; }

    public boolean isHiddenByPartnerOff() { return hiddenByPartnerOff; }
    public void setHiddenByPartnerOff(boolean v) { this.hiddenByPartnerOff = v; }

    public String getReviewRejectReason() { return reviewRejectReason; }
    public void setReviewRejectReason(String v) { this.reviewRejectReason = v; }

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
}
