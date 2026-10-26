package com.crosshub.backoffice.product.change;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * v1 "ProductChangeRequest" — 심사를 통과한(live/hidden) 상품을 연맹·업체가 수정/삭제/숨김전환하려 할 때 만드는 요청.
 * 크로스허브가 승인해야 실제 반영된다. type: edit|delete|hide, status: pending|approved|rejected.
 * proposedData(jsonb)는 edit일 때 FE ProductInput 원본 JSON — BE는 저장·전달만 한다.
 */
@Entity
@Table(name = "ProductChangeRequest")
public class ProductChangeRequest {
    @Id @Column(name = "id") private String id;
    @Column(name = "productId") private String productId;
    @Column(name = "type") private String type;
    @Column(name = "targetStatus") private String targetStatus;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposedData") private String proposedData;
    @Column(name = "status") private String status;
    @Column(name = "requestedById") private String requestedById;
    @Column(name = "resolvedById") private String resolvedById;
    @Column(name = "resolvedAt") private OffsetDateTime resolvedAt;
    @Column(name = "rejectReason") private String rejectReason;
    @Column(name = "createdAt") private OffsetDateTime createdAt;

    protected ProductChangeRequest() {}

    public static ProductChangeRequest pending(String productId, String type, String targetStatus, String proposedData, String requestedById) {
        ProductChangeRequest r = new ProductChangeRequest();
        r.id = UUID.randomUUID().toString().replace("-", "");
        r.productId = productId;
        r.type = type;
        r.targetStatus = targetStatus;
        r.proposedData = proposedData;
        r.status = "pending";
        r.requestedById = requestedById;
        r.createdAt = OffsetDateTime.now();
        return r;
    }

    public void approve(String resolverId) {
        this.status = "approved";
        this.resolvedById = resolverId;
        this.resolvedAt = OffsetDateTime.now();
    }

    public void reject(String resolverId, String reason) {
        this.status = "rejected";
        this.resolvedById = resolverId;
        this.resolvedAt = OffsetDateTime.now();
        this.rejectReason = reason;
    }

    public String getId() { return id; }
    public String getProductId() { return productId; }
    public String getType() { return type; }
    public String getTargetStatus() { return targetStatus; }
    public String getProposedData() { return proposedData; }
    public String getStatus() { return status; }
    public boolean isPending() { return "pending".equals(status); }
    public String getRequestedById() { return requestedById; }
    public String getRejectReason() { return rejectReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
