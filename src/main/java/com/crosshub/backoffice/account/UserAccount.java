package com.crosshub.backoffice.account;

import com.crosshub.backoffice.partner.Affiliate;
import com.crosshub.backoffice.partner.Partner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * v1 "User" 테이블 매핑.
 * - CallerService: 토큰 username → loginId 로 찾아 업체/역할을 붙이는 "sub → 서비스 DB" 브리지.
 * - 계정 관리(accounts) 화면용으로 name/상태/소속/가입일도 읽는다. 소속명은 Affiliate/Partner 조인.
 * 비밀번호 등 인증 정보는 Keycloak이 관리하므로 읽지 않는다.
 */
@Entity
@Table(name = "User")
public class UserAccount {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "loginId")
    private String loginId;

    @Column(name = "name")
    private String name;

    /** Prisma Role enum: crosshub | affiliate | partner */
    @Column(name = "role")
    private String role;

    @Column(name = "affiliateId")
    private String affiliateId;

    @Column(name = "partnerId")
    private String partnerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "affiliateId", insertable = false, updatable = false)
    private Affiliate affiliate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partnerId", insertable = false, updatable = false)
    private Partner partner;

    @Column(name = "mustChangePassword")
    private boolean mustChangePassword;

    @Column(name = "active")
    private boolean active;

    @Column(name = "lastLoginAt")
    private OffsetDateTime lastLoginAt;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    protected UserAccount() {
    }

    public String getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getName() { return name; }
    public String getRole() { return role; }
    public String getAffiliateId() { return affiliateId; }
    public String getPartnerId() { return partnerId; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public boolean isActive() { return active; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /** 소속명: 연맹 이름 우선, 없으면 업체 이름. */
    public String getOrgName() {
        if (affiliate != null) return affiliate.getName();
        if (partner != null) return partner.getName();
        return null;
    }
}
