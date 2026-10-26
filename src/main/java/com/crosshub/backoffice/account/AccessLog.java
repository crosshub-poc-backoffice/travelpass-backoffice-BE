package com.crosshub.backoffice.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** v1 "AccessLog"(메뉴 접근 기록). FE 가 요청마다 BE 기록 API(LogController)로 남기고, 보존 기간(30일) 지난 것은 NotificationCleanupJob 이 지운다 */
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

    public static AccessLog of(String userId, String menu, String kind, String path, String ip, String userAgent) {
        AccessLog a = new AccessLog();
        a.id = java.util.UUID.randomUUID().toString().replace("-", "");
        a.userId = userId;
        a.menu = menu;
        a.kind = kind;
        a.path = path;
        a.ip = ip;
        a.userAgent = userAgent;
        a.createdAt = OffsetDateTime.now();
        return a;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getMenu() { return menu; }
    public String getKind() { return kind; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
