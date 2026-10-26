package com.crosshub.backoffice.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** 토큰 → Caller: DB role 기준, 토큰 role 불일치 거부, 업체 범위(연맹 대행 업체) */
class CallerServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PartnerRepository partners = mock(PartnerRepository.class);
    private final CallerService service = new CallerService(users, partners);

    private static Jwt token(String username, String... roles) {
        return Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("preferred_username", username)
                .claim("resource_access", Map.of("backoffice-api", Map.of("roles", List.of(roles))))
                .build();
    }

    private void dbUser(String loginId, String role, String affiliateId, String partnerId) {
        when(users.findByLoginId(loginId)).thenReturn(Optional.of(UserAccount.issue(loginId, loginId, role, affiliateId, partnerId)));
    }

    @Test
    void 토큰_role과_DB_role이_같으면_통과() {
        dbUser("test-partner", "partner", null, "p1");
        Caller c = service.resolve(token("test-partner", "partner"));
        assertThat(c.role()).isEqualTo("partner");
        assertThat(c.canAccessPartner("p1")).isTrue();
        assertThat(c.canAccessPartner("p2")).isFalse();
    }

    @Test
    void 토큰_role과_DB_role이_다르면_403() {
        dbUser("test-partner", "partner", null, "p1");
        assertThatThrownBy(() -> service.resolve(token("test-partner", "crosshub")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void role이_여러_개면_403() {
        dbUser("test-partner", "partner", null, "p1");
        assertThatThrownBy(() -> service.resolve(token("test-partner", "partner", "crosshub")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void role이_없으면_403() {
        dbUser("test-partner", "partner", null, "p1");
        assertThatThrownBy(() -> service.resolve(token("test-partner"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void DB에_없는_계정은_403() {
        when(users.findByLoginId("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(token("ghost", "partner"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 연맹은_대행_업체만() {
        dbUser("test-federation", "affiliate", "a1", null);
        Partner proxied = mock(Partner.class);
        when(proxied.getId()).thenReturn("p-proxy");
        when(partners.findByProxyAffiliateId("a1")).thenReturn(List.of(proxied));
        Caller c = service.resolve(token("test-federation", "affiliate"));
        assertThat(c.partnerIds()).containsExactly("p-proxy");
        assertThat(c.canAccessPartner("p-proxy")).isTrue();
        assertThat(c.canAccessPartner("p-direct")).isFalse();
    }

    @Test
    void crosshub는_전체() {
        dbUser("test-crosshub", "crosshub", null, null);
        Caller c = service.resolve(token("test-crosshub", "crosshub"));
        assertThat(c.canAccessPartner("anything")).isTrue();
    }
}
