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
                .subject("kc-" + username)
                .claim("preferred_username", username)
                .claim("resource_access", Map.of("backoffice-api", Map.of("roles", List.of(roles))))
                .build();
    }

    private void dbUser(String loginId, String role, String affiliateId, String partnerId) {
        when(users.findByLoginId(loginId)).thenReturn(Optional.of(UserAccount.issue(loginId, loginId, role, affiliateId, partnerId)));
        when(users.bindKeycloakId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("kc-" + loginId))).thenReturn(1); // 첫 로그인 묶기 성공
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

    // ── sub ↔ 계정 묶기 (V4) ──

    @Test
    void 처음_로그인하면_sub를_묶는다() {
        UserAccount u = UserAccount.issue("old-user", "옛 계정", "partner", null, "p1");
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", "u1");
        when(users.findByKeycloakId("kc-old-user")).thenReturn(Optional.empty());
        when(users.findByLoginId("old-user")).thenReturn(Optional.of(u));
        when(users.bindKeycloakId("u1", "kc-old-user")).thenReturn(1);
        assertThat(service.resolve(token("old-user", "partner")).userId()).isEqualTo("u1");
        org.mockito.Mockito.verify(users).bindKeycloakId("u1", "kc-old-user");
    }

    @Test
    void 이미_다른_sub로_묶인_계정이면_403() {
        UserAccount u = UserAccount.issue("victim", "피해자", "crosshub", null, null, "kc-original");
        when(users.findByKeycloakId("kc-victim")).thenReturn(Optional.empty());
        when(users.findByLoginId("victim")).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> service.resolve(token("victim", "crosshub")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("identityMismatch");
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).bindKeycloakId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sub로_찾으면_username_검사만_하고_통과() {
        UserAccount u = UserAccount.issue("bound", "묶인 계정", "affiliate", "a1", null, "kc-bound");
        when(users.findByKeycloakId("kc-bound")).thenReturn(Optional.of(u));
        assertThat(service.resolve(token("bound", "affiliate")).role()).isEqualTo("affiliate");
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).findByLoginId("bound");
    }

    @Test
    void sub가_없는_토큰은_401() {
        Jwt noSub = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("preferred_username", "x").build();
        assertThatThrownBy(() -> service.resolve(noSub)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
    }
}
