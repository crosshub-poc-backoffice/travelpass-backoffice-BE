package com.crosshub.backoffice.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** v1 "LoginLog" — 마지막 접속 IP/브라우저 조회용(계정 관리 접속내역). */
@Entity
@Table(name = "LoginLog")
public class LoginLog {
    @Id @Column(name = "id") private String id;
    @Column(name = "userId") private String userId;
    @Column(name = "event") private String event;   // login | logout | failed
    @Column(name = "ip") private String ip;
    @Column(name = "userAgent") private String userAgent;
    @Column(name = "createdAt") private OffsetDateTime createdAt;

    protected LoginLog() {}
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
