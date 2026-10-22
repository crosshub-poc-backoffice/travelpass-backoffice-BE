package com.crosshub.backoffice.account;

import jakarta.servlet.http.HttpServletRequest;
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
 * - dev 모드(app.dev.enabled=true): 토큰 없이 호출 허용. Keycloak 붙이기 전 FE↔BE 연결을 먼저 검증하기 위한 용도.
 *     - 요청 헤더 X-Dev-Login-Id가 있으면 그 계정으로(요청마다 다른 사용자 흉내 — notifications처럼
 *       사용자 단위 데이터를 다룰 때 FE가 보낸다). Keycloak 붙으면 이 헤더는 지운다.
 *     - 헤더가 없으면 app.dev.login-id 설정값(그 계정으로 고정) → 지정 안 하면 모든 데이터를 보는
 *       crosshub 가짜 사용자 순으로 fallback.
 *     - JWT 인증 분기는 그대로라 secure 프로파일(app.dev.enabled=false)에는 영향 없음.
 */
@Service
public class CallerService {

    private static final String DEV_LOGIN_ID_HEADER = "X-Dev-Login-Id";

    private final UserAccountRepository users;
    private final HttpServletRequest request;

    @Value("${app.dev.enabled:false}")
    private boolean devEnabled;

    @Value("${app.dev.login-id:}")
    private String devLoginId;

    public CallerService(UserAccountRepository users, HttpServletRequest request) {
        this.users = users;
        this.request = request;
    }

    /** SecurityContext에서 현재 요청자를 해석한다 (컨트롤러가 Jwt를 직접 안 다뤄도 됨). */
    public Caller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return resolve(jwt);
        }
        if (devEnabled) {
            String headerLoginId = request.getHeader(DEV_LOGIN_ID_HEADER);
            if (headerLoginId != null && !headerLoginId.isBlank()) {
                return fromLoginId(headerLoginId);
            }
            if (devLoginId != null && !devLoginId.isBlank()) {
                return fromLoginId(devLoginId);
            }
            // 모든 데이터 열람 가능한 dev용 crosshub 사용자
            return new Caller("dev", "dev", "crosshub", null);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
    }

    public Caller resolve(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "토큰에 preferred_username이 없습니다.");
        }
        return fromLoginId(username);
    }

    private Caller fromLoginId(String loginId) {
        UserAccount u = users.findByLoginId(loginId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "백오피스에 등록되지 않은 계정입니다: " + loginId));
        if (!u.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "비활성화된 계정입니다.");
        }
        return new Caller(u.getId(), u.getLoginId(), u.getRole(), u.getPartnerId());
    }
}
