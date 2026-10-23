package com.crosshub.backoffice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** 토큰 aud/azp 검증과 client role → ROLE_* 변환 */
class SecurityConfigUnitTest {

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }

    private final SecurityConfig.ClientAudienceValidator aud = new SecurityConfig.ClientAudienceValidator("poc-backoffice");
    private final SecurityConfig.KeycloakClientRoleConverter roles = new SecurityConfig.KeycloakClientRoleConverter("poc-backoffice");

    @Test
    void aud에_client가_있으면_통과() {
        assertThat(aud.validate(jwt(Map.of("aud", List.of("account", "poc-backoffice")))).hasErrors()).isFalse();
    }

    @Test
    void azp가_client면_통과() {
        assertThat(aud.validate(jwt(Map.of("aud", List.of("account"), "azp", "poc-backoffice"))).hasErrors()).isFalse();
    }

    @Test
    void 다른_client_토큰은_거부() {
        assertThat(aud.validate(jwt(Map.of("aud", List.of("account"), "azp", "backoffice-admin-api"))).hasErrors()).isTrue();
    }

    @Test
    void client_role을_ROLE_로_변환() {
        var auths = roles.convert(jwt(Map.of("resource_access", Map.of("poc-backoffice", Map.of("roles", List.of("partner"))))));
        assertThat(auths).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_PARTNER");
    }

    @Test
    void 다른_client의_role은_무시() {
        var auths = roles.convert(jwt(Map.of("resource_access", Map.of("account", Map.of("roles", List.of("crosshub"))))));
        assertThat(auths).isEmpty();
    }
}
