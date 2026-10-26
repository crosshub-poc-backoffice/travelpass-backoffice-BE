package com.crosshub.backoffice.product.inspect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** "ProductInspection"(V3) — 상품 검수 결과 1건. 최초 심사면 changeRequestId null. findings 는 JSON 원본 */
@Entity
@Table(name = "ProductInspection")
public class ProductInspection {

    @Id @Column(name = "id") private String id;
    @Column(name = "productId") private String productId;
    @Column(name = "changeRequestId") private String changeRequestId;
    @Column(name = "status") private String status;
    @Column(name = "verdict") private String verdict;
    @Column(name = "aiRan") private boolean aiRan;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "findings") private String findings;
    @Column(name = "createdAt") private OffsetDateTime createdAt;

    protected ProductInspection() {}

    public static ProductInspection of(String productId, String changeRequestId, String status, String verdict, boolean aiRan, String findingsJson) {
        ProductInspection i = new ProductInspection();
        i.id = UUID.randomUUID().toString().replace("-", "");
        i.productId = productId;
        i.changeRequestId = changeRequestId;
        i.status = status;
        i.verdict = verdict;
        i.aiRan = aiRan;
        i.findings = findingsJson;
        i.createdAt = OffsetDateTime.now();
        return i;
    }

    public String getId() { return id; }
    public String getProductId() { return productId; }
    public String getChangeRequestId() { return changeRequestId; }
    public String getStatus() { return status; }
    public String getVerdict() { return verdict; }
    public boolean isAiRan() { return aiRan; }
    public String getFindings() { return findings; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
