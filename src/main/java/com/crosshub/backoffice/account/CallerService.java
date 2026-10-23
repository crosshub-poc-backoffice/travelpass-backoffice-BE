package com.crosshub.backoffice.account;

import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * - dev 모드(app.dev.enabled=true, 로컬 전용): 토큰 없이 호출 허용. app.dev.login-id 계정으로 고정,
 *   비우면 모든 데이터를 보는 crosshub 가짜 사용자. (예전 X-Dev-Login-Id 헤더 흉내는 Keycloak 전환으로 제거)
 */
@Service
public class CallerService {

    private static final Logger log = LoggerFactory.getLogger(CallerService.class);
    /** role이 담긴 client (토큰의 resource_access.<client>.roles) */
    static final String APP_CLIENT_ID = "poc-backoffice";

    private final UserAccountRepository users;
    private final PartnerRepository partners;

    @Value("${app.dev.enabled:false}")
    private boolean devEnabled;

    @Value("${app.dev.login-id:}")
    private String devLoginId;

    public CallerService(UserAccountRepository users, PartnerRepository partners) {
        this.users = users;
        this.partners = partners;
    }

    /** SecurityContext에서 현재 요청자를 해석한다 (컨트롤러가 Jwt를 직접 안 다뤄도 됨). */
    public Caller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return resolve(jwt);
        }
        if (devEnabled) {
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
        Caller caller = fromLoginId(username);
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

    /** 토큰의 resource_access.poc-backoffice.roles */
    static Set<String> tokenRoles(Jwt jwt) {
        Object ra = jwt.getClaim("resource_access");
        if (!(ra instanceof Map<?, ?> m) || !(m.get(APP_CLIENT_ID) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    private Caller fromLoginId(String loginId) {
        UserAccount u = users.findByLoginId(loginId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "백오피스에 등록되지 않은 계정입니다: " + loginId));
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
