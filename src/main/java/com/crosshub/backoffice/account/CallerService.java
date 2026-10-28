package com.crosshub.backoffice.account;

import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 현재 요청자(Caller) 해석.
 *
 * - 운영: Keycloak JWT의 preferred_username(= Keycloak username)을 User.loginId 로 찾아 업체/역할을 붙인다.
 *   권한 판단 기준은 DB(User.role / partnerId / affiliateId)다. 토큰 role과 DB role이 다르면 DB가 이긴다
 *   (토큰 role은 SecurityConfig의 1차 거름망일 뿐).
 * - 크로스허브 직원은 발급 없이 첫 로그인 때 자동 등록된다(CrosshubEnroller). 연맹·업체는 소속을 정해야 해서 발급이 먼저다.
 * - 인증 끄기(app.auth.disabled=true, 로컬 전용): 토큰 없이 호출 허용. app.auth.dev-login-id 계정으로 고정,
 *   비우면 모든 데이터를 보는 crosshub 가짜 사용자. (예전 X-Dev-Login-Id 헤더 흉내는 Keycloak 전환으로 제거)
 */
@Service
public class CallerService {

    private static final Logger log = LoggerFactory.getLogger(CallerService.class);
    private final UserAccountRepository users;
    private final PartnerRepository partners;
    private final CrosshubEnroller enroller;

    /** role이 담긴 BE 이름표 client (토큰의 resource_access.<client>.roles). SecurityConfig 의 aud 검사와 같은 설정값 */
    @Value("${app.keycloak.audience}")
    private String audience = "tp-office-partner-api";

    @Value("${app.auth.disabled}")
    private boolean authDisabled;

    @Value("${app.auth.dev-login-id:}")
    private String devLoginId;

    public CallerService(UserAccountRepository users, PartnerRepository partners, CrosshubEnroller enroller) {
        this.users = users;
        this.partners = partners;
        this.enroller = enroller;
    }

    /** SecurityContext에서 현재 요청자를 해석한다 (컨트롤러가 Jwt를 직접 안 다뤄도 됨). */
    public Caller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return resolve(jwt);
        }
        if (authDisabled) {
            if (devLoginId != null && !devLoginId.isBlank()) {
                return fromLoginId(devLoginId);
            }
            // 모든 데이터 열람 가능한 dev용 crosshub 사용자
            return new Caller("dev", "dev", "crosshub", null, null, List.of());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
    }

    public Caller resolve(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "토큰에 preferred_username이 없습니다.");
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "토큰에 sub가 없습니다.");
        }
        Caller caller = toCaller(boundAccount(jwt, username, sub));
        // 토큰 role(Keycloak)과 DB role(User.role)은 정확히 같아야 한다. 다르면 설정 실수라 요청을 거절하고 경고 로그를 남긴다.
        // (role이 여러 개 붙은 경우도 거절 — 토큰 role로 URL 권한이 넓어지는 것을 막는다)
        Set<String> tokenRoles = tokenRoles(jwt);
        if (!tokenRoles.equals(Set.of(caller.role()))) {
            log.warn("[auth] role 불일치 — loginId={} 토큰 role={} DB role={}. Keycloak client role과 User.role을 맞춰야 합니다.",
                    username, tokenRoles, caller.role());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "roleMismatch");
        }
        return caller;
    }

    /** 토큰의 resource_access.<audience>.roles */
    Set<String> tokenRoles(Jwt jwt) {
        Object ra = jwt.getClaim("resource_access");
        if (!(ra instanceof Map<?, ?> m) || !(m.get(audience) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 토큰 사용자(sub) ↔ 백오피스 계정. sub 로 먼저 찾고, 없으면 username 으로 찾아 처음 본 sub 를 묶는다 (V4 전 계정의 첫 로그인).
     * 이미 다른 sub 가 묶인 계정이면 403 identityMismatch — Keycloak 에서 같은 이름의 사용자를 새로 만들어
     * 옛 계정의 권한을 가져가는 것을 막는다. 정상적으로 다시 만든 경우는 User.keycloakId 를 비우면 다시 묶인다.
     * 둘 다 없으면 크로스허브만 자동 등록, 나머지는 403 (미등록).
     */
    UserAccount boundAccount(Jwt jwt, String username, String sub) {
        Optional<UserAccount> bySub = users.findByKeycloakId(sub);
        if (bySub.isPresent()) {
            UserAccount u = bySub.get();
            // Keycloak 은 username 변경을 막아 두었다(editUsernameAllowed=false). 다르면 데이터가 꼬인 것
            if (!u.getLoginId().equals(username)) {
                log.warn("[auth] sub 로 찾은 계정과 토큰 username 이 다름 — sub={} loginId={} token username={}", sub, u.getLoginId(), username);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "identityMismatch");
            }
            return u;
        }
        Optional<UserAccount> byName = users.findByLoginId(username);
        if (byName.isEmpty()) return enrollCrosshub(jwt, username, sub);
        UserAccount u = byName.get();
        if (u.getKeycloakId() != null) {
            log.warn("[auth] 다른 Keycloak 사용자로 묶인 계정에 로그인 시도 — loginId={} 묶인 sub={} 토큰 sub={}", username, u.getKeycloakId(), sub);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "identityMismatch");
        }
        if (users.bindKeycloakId(u.getId(), sub) == 0) {
            // 동시에 들어온 첫 로그인끼리 경합 — 다시 읽어 같은 sub 인지만 본다
            UserAccount again = users.findById(u.getId()).orElseThrow();
            if (!sub.equals(again.getKeycloakId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "identityMismatch");
            return again;
        }
        log.info("[auth] 계정을 Keycloak 사용자와 묶음 — loginId={} sub={}", username, sub);
        return u;
    }

    /**
     * DB 에 없는 사용자의 첫 로그인. 토큰 role 이 crosshub 하나뿐이면(= Keycloak /crosshub 소속) User 를 만든다.
     * 비활성화된 사람은 DB 에 남아 있어 여기로 오지 않는다 — 다시 로그인해도 막힌다.
     */
    private UserAccount enrollCrosshub(Jwt jwt, String username, String sub) {
        if (!tokenRoles(jwt).equals(Set.of("crosshub"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "백오피스에 등록되지 않은 계정입니다: " + username);
        }
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = username;
        if (name.length() > 50) name = name.substring(0, 50);
        try {
            return enroller.enroll(username, name.trim(), sub);
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 첫 요청끼리 경합 — 먼저 만든 게 같은 sub 면 그걸 쓴다
            return users.findByKeycloakId(sub)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "identityMismatch"));
        }
    }

    private Caller fromLoginId(String loginId) {
        UserAccount u = users.findByLoginId(loginId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "백오피스에 등록되지 않은 계정입니다: " + loginId));
        return toCaller(u);
    }

    private Caller toCaller(UserAccount u) {
        if (!u.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "비활성화된 계정입니다.");
        }
        return new Caller(u.getId(), u.getLoginId(), u.getRole(), u.getPartnerId(), u.getAffiliateId(), scopeOf(u));
    }

    /** 업체 범위. FE의 findManagedPartners(lib/server/auth/session.ts)와 같은 규칙. */
    private List<String> scopeOf(UserAccount u) {
        if ("crosshub".equals(u.getRole())) return List.of();
        if ("affiliate".equals(u.getRole())) {
            // affiliateId가 비면 proxyAffiliateId=null(직접 운영 업체 전체)로 조회되므로 반드시 막는다
            if (u.getAffiliateId() == null) return List.of();
            return partners.findByProxyAffiliateId(u.getAffiliateId()).stream().map(Partner::getId).toList();
        }
        return u.getPartnerId() == null ? List.of() : List.of(u.getPartnerId());
    }

    /** crosshub 전용 API용. 아니면 403. */
    public Caller requireCrosshub() {
        Caller c = current();
        if (!c.isCrosshub()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "크로스허브 전용 기능입니다.");
        }
        return c;
    }
}
