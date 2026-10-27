package com.crosshub.backoffice.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** v1 "LoginLog" — 로그인·로그아웃·실패 기록. FE 로그인 콜백·로그아웃이 BE 기록 API(LogController)로 남긴다. */
@Entity
@Table(name = "LoginLog")
public class LoginLog {
    @Id @Column(name = "id") private String id;
    @Column(name = "userId") private String userId;
    @Column(name = "loginId") private String loginId;
    @Column(name = "event") private String event;   // login | logout | failed (Postgres enum LoginEvent)
    @Column(name = "failReason") private String failReason;
    @Column(name = "ip") private String ip;
    @Column(name = "userAgent") private String userAgent;
    @Column(name = "createdAt") private OffsetDateTime createdAt;

    protected LoginLog() {}

    public static LoginLog of(String userId, String loginId, String event, String failReason, String ip, String userAgent) {
        LoginLog l = new LoginLog();
        l.id = java.util.UUID.randomUUID().toString().replace("-", "");
        l.userId = userId;
        l.loginId = loginId;
        l.event = event;
        l.failReason = failReason;
        l.ip = ip;
        l.userAgent = userAgent;
        l.createdAt = OffsetDateTime.now();
        return l;
    }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
