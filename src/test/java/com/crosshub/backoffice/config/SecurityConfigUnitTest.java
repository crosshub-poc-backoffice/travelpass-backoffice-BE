package com.crosshub.backoffice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** 토큰 aud 검증, client role → ROLE_* 변환, issuer 별 검증기 선택 */
class SecurityConfigUnitTest {

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }

    private final SecurityConfig.ClientAudienceValidator aud = new SecurityConfig.ClientAudienceValidator("backoffice-api");
    private final SecurityConfig.KeycloakClientRoleConverter roles = new SecurityConfig.KeycloakClientRoleConverter("backoffice-api");

    @Test
    void aud에_client가_있으면_통과() {
        assertThat(aud.validate(jwt(Map.of("aud", List.of("account", "backoffice-api")))).hasErrors()).isFalse();
    }

    @Test
    void aud에_없으면_azp가_로그인_client여도_거부() {
        assertThat(aud.validate(jwt(Map.of("aud", List.of("account"), "azp", "poc-backoffice"))).hasErrors()).isTrue();
    }

    @Test
    void 다른_client_토큰은_거부() {
        assertThat(aud.validate(jwt(Map.of("aud", List.of("account"), "azp", "backoffice-admin-api"))).hasErrors()).isTrue();
    }

    @Test
    void client_role을_ROLE_로_변환() {
        var auths = roles.convert(jwt(Map.of("resource_access", Map.of("backoffice-api", Map.of("roles", List.of("partner"))))));
        assertThat(auths).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_PARTNER");
    }

    @Test
    void 고객_권한은_ROLE_CUSTOMER_하나() {
        var auths = SecurityConfig.CUSTOMER_AUTHORITIES.convert(jwt(Map.of("sub", "x")));
        assertThat(auths).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void issuer_resolver는_모르는_issuer를_거부하고_아는_issuer만_검증으로_넘긴다() {
        // Spring Security 6: resolve() 는 항상 검증기를 돌려주고, iss 조회는 authenticate() 때 한다
        var resolver = new SecurityConfig().authenticationManagerResolver(
                "http://127.0.0.1:1/realms/crosshub", "http://127.0.0.1:1/realms/idblock");
        // 모르는 issuer → InvalidBearerTokenException (401)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authenticate(resolver, "http://127.0.0.1:1/realms/other"))
                .isInstanceOf(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class)
                .hasMessageContaining("issuer");
        // 아는 issuer → 그 issuer 의 검증기까지 간다 (JWKS 를 받으러 가다 연결 실패 = issuer 는 통과했다는 뜻)
        for (String iss : List.of("http://127.0.0.1:1/realms/crosshub", "http://127.0.0.1:1/realms/idblock")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> authenticate(resolver, iss))
                    .isNotInstanceOf(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class);
        }
    }

    private static void authenticate(
            org.springframework.security.authentication.AuthenticationManagerResolver<jakarta.servlet.http.HttpServletRequest> resolver,
            String iss) {
        var req = bearer(iss);
        String token = req.getHeader("Authorization").substring("Bearer ".length());
        resolver.resolve(req).authenticate(
                new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
    }

    /** iss 만 들어 있는 서명 없는 JWT 를 Authorization 헤더에 실은 요청 (resolver 는 iss 만 읽는다) */
    private static jakarta.servlet.http.HttpServletRequest bearer(String iss) {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iss\":\"" + iss + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9." + payload + ".sig");
        return req;
    }

    @Test
    void 다른_client의_role은_무시() {
        var auths = roles.convert(jwt(Map.of("resource_access", Map.of("account", Map.of("roles", List.of("crosshub"))))));
        assertThat(auths).isEmpty();
    }
}
