package com.crosshub.backoffice.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** v1 "AccessLog"(메뉴 접근 기록). 지금은 FE 가 쓰고 BE 는 보존 기간 지난 것만 지운다 (NotificationCleanupJob). 조회는 다음 단계 */
@Entity
@Table(name = "AccessLog")
public class AccessLog {
    @Id @Column(name = "id") private String id;
    @Column(name = "userId") private String userId;
    @Column(name = "menu") private String menu;
    @Column(name = "kind") private String kind;
    @Column(name = "path") private String path;
    @Column(name = "ip") private String ip;
    @Column(name = "userAgent") private String userAgent;
    @Column(name = "createdAt") private OffsetDateTime createdAt;

    protected AccessLog() {}

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getMenu() { return menu; }
    public String getKind() { return kind; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
