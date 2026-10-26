package com.crosshub.backoffice.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** v1 "Notification" — 저장형(이벤트) 알림. userId 기준. payload는 jsonb 원본 보존. */
@Entity
@Table(name = "Notification")
public class Notification {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "userId")
    private String userId;

    @Column(name = "type")
    private String type;

    @Column(name = "sourceId")
    private String sourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    @Column(name = "read")
    private boolean read;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    protected Notification() {
    }

    /** 새 알림. payload 는 JSON 문자열 (type 포함, FE NotificationPayload 모양) */
    public static Notification of(String userId, String type, String sourceId, String payloadJson) {
        Notification n = new Notification();
        n.id = java.util.UUID.randomUUID().toString().replace("-", "");
        n.userId = userId;
        n.type = type;
        n.sourceId = sourceId;
        n.payload = payloadJson;
        n.read = false;
        n.createdAt = OffsetDateTime.now();
        return n;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getType() { return type; }
    public String getSourceId() { return sourceId; }
    public String getPayload() { return payload; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
