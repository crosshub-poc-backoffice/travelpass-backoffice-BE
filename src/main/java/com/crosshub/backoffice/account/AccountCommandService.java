package com.crosshub.backoffice.account;

import com.crosshub.backoffice.account.keycloak.KeycloakAdminClient;
import com.crosshub.backoffice.account.keycloak.KeycloakAdminClient.KeycloakSyncException;
import com.crosshub.backoffice.notification.NotificationRepository;
import com.crosshub.backoffice.partner.Affiliate;
import com.crosshub.backoffice.partner.AffiliateRepository;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 발급·비밀번호 초기화·활성 토글 (크로스허브 전용).
 * 순서: Keycloak 먼저 → 성공하면 DB. DB가 실패하면 방금 만든 Keycloak 유저를 지워 되돌린다
 * → 두 시스템이 항상 같이 있거나 같이 없다. 로그인이 Keycloak 전용이라 Keycloak 반영 실패면 작업 자체를 거절한다.
 * FE 로그인 세션(Session 테이블) 정리는 세션 주인인 FE가 한다.
 */
@Service
public class AccountCommandService {

    private static final Logger log = LoggerFactory.getLogger(AccountCommandService.class);
    private static final Set<String> ROLES = Set.of("crosshub", "affiliate", "partner");
    private static final Pattern LOGIN_ID = Pattern.compile("^[a-z0-9._-]{4,30}$");

    private final UserAccountRepository users;
    private final AffiliateRepository affiliates;
    private final PartnerRepository partners;
    private final AuditLogRepository audits;
    private final NotificationRepository notifications;
    private final KeycloakAdminClient keycloak;
    private final TransactionTemplate tx;

    public AccountCommandService(UserAccountRepository users, AffiliateRepository affiliates, PartnerRepository partners,
            AuditLogRepository audits, NotificationRepository notifications, KeycloakAdminClient keycloak, TransactionTemplate tx) {
        this.users = users;
        this.affiliates = affiliates;
        this.partners = partners;
        this.audits = audits;
        this.notifications = notifications;
        this.keycloak = keycloak;
        this.tx = tx;
    }

    public record IssueRequest(String role, String name, String loginId, String affiliateId, String partnerId, String newAffiliateName) {}
    public record Issued(String id, String loginId, String partnerId, String tempPassword) {}
    public record Reset(String loginId, String tempPassword) {}

    /** 계정 발급. 연맹 계정은 새 연맹(newAffiliateName)을 만들며 발급할 수 있다. 소속(연맹·업체)당 계정 1개 */
    public Issued issue(Caller actor, IssueRequest req) {
        String role = req.role();
        String loginId = req.loginId() == null ? "" : req.loginId().trim().toLowerCase();
        String inputName = req.name() == null ? "" : req.name().trim();
        String newAffiliateName = "affiliate".equals(role) && req.newAffiliateName() != null && !req.newAffiliateName().isBlank()
                ? req.newAffiliateName().trim() : null;
        String affiliateId = "affiliate".equals(role) && newAffiliateName == null ? blankToNull(req.affiliateId()) : null;
        String partnerId = "partner".equals(role) ? blankToNull(req.partnerId()) : null;

        // 입력 검증 (FE에서도 하지만 BE가 최종)
        if (!ROLES.contains(role) || loginId.isEmpty() || ("crosshub".equals(role) && inputName.isEmpty())) fail(HttpStatus.BAD_REQUEST, "required");
        if (inputName.length() > 50 || (newAffiliateName != null && newAffiliateName.length() > 50)) fail(HttpStatus.BAD_REQUEST, "nameLength");
        if (!LOGIN_ID.matcher(loginId).matches()) fail(HttpStatus.BAD_REQUEST, "loginIdFormat");
        boolean orgMatches = ("crosshub".equals(role) && blankToNull(req.affiliateId()) == null && blankToNull(req.partnerId()) == null)
                || ("affiliate".equals(role) && (affiliateId != null || newAffiliateName != null) && blankToNull(req.partnerId()) == null)
                || ("partner".equals(role) && partnerId != null && blankToNull(req.affiliateId()) == null);
        if (!orgMatches) fail(HttpStatus.BAD_REQUEST, "invalidOrg");

        // 소속 확인 → 계정 이름은 소속명 (crosshub는 입력한 담당자 이름)
        String name = inputName;
        if (newAffiliateName != null) {
            if (affiliates.existsByNameIgnoreCase(newAffiliateName)) fail(HttpStatus.CONFLICT, "affiliateNameTaken");
            name = newAffiliateName;
        } else if ("affiliate".equals(role)) {
            Affiliate a = affiliates.findById(affiliateId).orElseThrow(() -> new AccountException(HttpStatus.BAD_REQUEST, "invalidOrg"));
            if (users.countByAffiliateId(affiliateId) > 0) fail(HttpStatus.CONFLICT, "orgHasAccount");
            name = a.getName();
        } else if ("partner".equals(role)) {
            Partner p = partners.findById(partnerId).orElseThrow(() -> new AccountException(HttpStatus.BAD_REQUEST, "invalidOrg"));
            if (p.getProxyAffiliateId() != null) fail(HttpStatus.BAD_REQUEST, "invalidOrg"); // 대행 업체는 연맹이 처리
            if (users.countByPartnerId(partnerId) > 0) fail(HttpStatus.CONFLICT, "orgHasAccount");
            name = p.getName();
        }
        if (users.existsByLoginId(loginId)) fail(HttpStatus.CONFLICT, "loginIdTaken");

        // 1) Keycloak
        requireKeycloak();
        String tempPassword = TempPassword.generate();
        String keycloakUserId;
        try {
            // DB엔 없는데(위에서 확인) Keycloak에 같은 username이 있으면:
            //  - 이 앱 role이 붙은 유저 = 예전 발급 롤백이 실패해 남은 고아 → 지우고 다시 발급
            //  - 아니면 이 앱과 무관하게 만들어진 유저 → 건드리지 않고 loginIdTaken
            String existing = keycloak.findUserIdByUsername(loginId);
            if (existing != null) {
                if (!keycloak.hasAppRole(existing)) fail(HttpStatus.CONFLICT, "loginIdTaken");
                log.warn("[keycloak] 고아 유저 정리 후 재발급: loginId={}, keycloakId={}", loginId, existing);
                if (!keycloak.deleteUser(existing)) throw new KeycloakSyncException("고아 유저 삭제 실패: " + loginId);
            }
            keycloakUserId = keycloak.createUser(loginId, name, role, tempPassword);
        } catch (KeycloakSyncException e) {
            log.error("[keycloak] 계정 발급 실패: {}", loginId, e);
            throw new AccountException(HttpStatus.BAD_GATEWAY, "keycloakSyncFailed");
        }

        // 2) DB (실패하면 Keycloak 롤백)
        final String finalName = name;
        try {
            UserAccount created = tx.execute(status -> {
                String affId = affiliateId;
                if (newAffiliateName != null) {
                    Affiliate a = affiliates.save(Affiliate.create(newAffiliateName));
                    affId = a.getId();
                    audits.save(AuditLog.of(actor.userId(), "account_affiliate_create", null, a.getId(), newAffiliateName));
                }
                UserAccount u = users.save(UserAccount.issue(loginId, finalName, role, affId, partnerId));
                audits.save(AuditLog.of(actor.userId(), "account_issue", u.getPartnerId(), u.getId(), u.getLoginId()));
                return u;
            });
            return new Issued(created.getId(), created.getLoginId(), created.getPartnerId(), tempPassword);
        } catch (DataIntegrityViolationException e) {
            rollbackKeycloakUser(loginId, keycloakUserId);
            throw new AccountException(HttpStatus.CONFLICT, "loginIdTaken");
        } catch (RuntimeException e) {
            rollbackKeycloakUser(loginId, keycloakUserId);
            throw e;
        }
    }

    /** 임시 비밀번호로 초기화 (Keycloak). 첫 로그인 때 변경 강제, 그 계정의 Keycloak 세션 종료 */
    public Reset resetPassword(Caller actor, String userId) {
        if (actor.userId().equals(userId)) fail(HttpStatus.BAD_REQUEST, "resetSelf");
        UserAccount u = users.findById(userId).orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "notFound"));
        requireKeycloak();
        String tempPassword = TempPassword.generate();
        try {
            keycloak.resetPassword(u.getLoginId(), u.getName(), u.getRole(), tempPassword);
        } catch (KeycloakSyncException e) {
            log.error("[keycloak] 비번 초기화 실패: {}", u.getLoginId(), e);
            throw new AccountException(HttpStatus.BAD_GATEWAY, "keycloakSyncFailed");
        }
        audits.save(AuditLog.of(actor.userId(), "account_password_reset", u.getPartnerId(), u.getId(), u.getLoginId()));
        return new Reset(u.getLoginId(), tempPassword);
    }

    /** 활성/비활성 뒤집기. 본인 계정은 막는다. 비활성화하면 Keycloak 세션도 종료, 재활성화 때 쌓인 알림은 읽음 처리 */
    public boolean toggleActive(Caller actor, String userId) {
        if (actor.userId().equals(userId)) fail(HttpStatus.BAD_REQUEST, "toggleSelf");
        UserAccount u = users.findById(userId).orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "notFound"));
        boolean active = !u.isActive();
        requireKeycloak();
        try {
            keycloak.setEnabled(u.getLoginId(), active);
        } catch (KeycloakSyncException e) {
            log.error("[keycloak] 활성 토글 실패: {}", u.getLoginId(), e);
            throw new AccountException(HttpStatus.BAD_GATEWAY, "keycloakSyncFailed");
        }
        try {
            tx.executeWithoutResult(status -> {
                UserAccount managed = users.findById(userId).orElseThrow();
                managed.setActive(active);
                if (active) notifications.markAllRead(userId);
                audits.save(AuditLog.of(actor.userId(), active ? "account_activate" : "account_deactivate",
                        managed.getPartnerId(), managed.getId(), managed.getLoginId()));
            });
        } catch (RuntimeException e) {
            // DB가 실패하면 Keycloak도 원래 상태로 되돌려 두 시스템이 어긋나지 않게 한다
            try {
                keycloak.setEnabled(u.getLoginId(), !active);
            } catch (RuntimeException revertError) {
                log.error("[keycloak] 활성 토글 되돌리기 실패 — Keycloak enabled={}, DB active={} 로 어긋남: {}",
                        active, !active, u.getLoginId(), revertError);
            }
            throw e;
        }
        return active;
    }

    /** 발급 중 DB가 실패했을 때 방금 만든 Keycloak 유저를 지운다. 지우기도 실패하면 고아로 남았다고 ERROR로 알린다
     *  (같은 loginId로 다시 발급하면 issue()가 고아로 판단해 정리한다) */
    private void rollbackKeycloakUser(String loginId, String keycloakUserId) {
        if (!keycloak.deleteUser(keycloakUserId)) {
            log.error("[keycloak] 발급 롤백 실패 — 고아 유저 남음: loginId={}, keycloakId={} (같은 ID로 재발급하면 자동 정리)",
                    loginId, keycloakUserId);
        }
    }

    private void requireKeycloak() {
        if (!keycloak.enabled()) {
            log.error("KEYCLOAK_SVC_CLIENT_SECRET 미설정 — 계정 발급·초기화·토글 불가");
            throw new AccountException(HttpStatus.BAD_GATEWAY, "keycloakSyncFailed");
        }
    }

    private static void fail(HttpStatus status, String code) {
        throw new AccountException(status, code);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
