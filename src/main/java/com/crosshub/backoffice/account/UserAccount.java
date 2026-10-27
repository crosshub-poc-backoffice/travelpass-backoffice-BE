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
import java.util.UUID;

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

    @Column(name = "active")
    private boolean active;

    @Column(name = "lastLoginAt")
    private OffsetDateTime lastLoginAt;

    /** Keycloak 사용자 id (토큰의 sub). 발급 때 채우고, 옛 계정은 첫 로그인 때 묶는다 (V4, CallerService) */
    @Column(name = "keycloakId")
    private String keycloakId;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    /** Prisma @updatedAt — DB 기본값이 없어 직접 채운다 */
    @Column(name = "updatedAt")
    private OffsetDateTime updatedAt;

    protected UserAccount() {
    }

    /** 계정 발급. 비밀번호는 Keycloak이 관리하므로 여기엔 없다 */
    public static UserAccount issue(String loginId, String name, String role, String affiliateId, String partnerId) {
        return issue(loginId, name, role, affiliateId, partnerId, null);
    }

    public static UserAccount issue(String loginId, String name, String role, String affiliateId, String partnerId, String keycloakId) {
        UserAccount u = new UserAccount();
        u.id = UUID.randomUUID().toString().replace("-", "");
        u.loginId = loginId;
        u.name = name;
        u.role = role;
        u.affiliateId = affiliateId;
        u.partnerId = partnerId;
        u.keycloakId = keycloakId;
        u.active = true;
        u.createdAt = u.updatedAt = OffsetDateTime.now();
        return u;
    }

    /** 로그인 성공 (FE 콜백이 BE 에 알린다). 첫 로그인이면 "대기"가 풀린다 */
    public void markLoggedIn() {
        this.lastLoginAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getKeycloakId() { return keycloakId; }
    public String getName() { return name; }
    public String getRole() { return role; }
    public String getAffiliateId() { return affiliateId; }
    public String getPartnerId() { return partnerId; }
    /** 발급 후 아직 한 번도 로그인 안 함 (= 대기). 첫 로그인 때 Keycloak이 비밀번호 변경을 강제한다 */
    public boolean isPending() { return lastLoginAt == null; }
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
