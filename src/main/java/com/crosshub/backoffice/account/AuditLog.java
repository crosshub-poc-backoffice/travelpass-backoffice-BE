package com.crosshub.backoffice.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** v1 "AuditLog"(작업 이력) — 쓰기 전용. detail(jsonb)은 "바뀐 값 {필드: [이전값, 새값]}" 형태의 원본 JSON 문자열. */
@Entity
@Table(name = "AuditLog")
public class AuditLog {
    @Id @Column(name = "id") private String id;
    @Column(name = "userId") private String userId;
    @Column(name = "partnerId") private String partnerId;
    @Column(name = "action") private String action;
    @Column(name = "targetId") private String targetId;
    @Column(name = "targetName") private String targetName;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail") private String detail;
    @Column(name = "createdAt") private OffsetDateTime createdAt;

    protected AuditLog() {}

    public static AuditLog of(String actorId, String action, String partnerId, String targetId, String targetName) {
        return of(actorId, action, partnerId, targetId, targetName, null);
    }

    public static AuditLog of(String actorId, String action, String partnerId, String targetId, String targetName, String detail) {
        AuditLog a = new AuditLog();
        a.id = UUID.randomUUID().toString().replace("-", "");
        a.userId = actorId;
        a.action = action;
        a.partnerId = partnerId;
        a.targetId = targetId;
        a.targetName = targetName;
        a.detail = detail;
        a.createdAt = OffsetDateTime.now();
        return a;
    }
}
