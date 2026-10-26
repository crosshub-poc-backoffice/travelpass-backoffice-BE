package com.crosshub.backoffice.account;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosshub.backoffice.config.SecurityConfig;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** /api/v1/me: 토큰 없음 401, 고객 토큰 403, 직원 토큰 200 + 업체 범위 */
@WebMvcTest(MeController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.dev.enabled=false", "app.cors.allowed-origins=http://localhost:3000"})
class MeApiTest {

    @Autowired MockMvc mvc;
    @MockBean CallerService callers;
    @MockBean UserAccountRepository users;
    @MockBean PartnerRepository partners;

    @Test
    void 토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void 고객_토큰은_403() throws Exception {
        mvc.perform(get("/api/v1/me").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void 연맹_계정은_대행_업체_목록을_받는다() throws Exception {
        when(callers.current()).thenReturn(new Caller("u1", "fed", "affiliate", null, "a1", List.of("p2", "p1")));
        UserAccount u = Mockito.mock(UserAccount.class);
        when(u.getId()).thenReturn("u1"); when(u.getLoginId()).thenReturn("fed"); when(u.getName()).thenReturn("연맹 담당");
        when(u.getRole()).thenReturn("affiliate"); when(u.getAffiliateId()).thenReturn("a1"); when(u.getOrgName()).thenReturn("규슈연맹");
        when(users.findById("u1")).thenReturn(Optional.of(u));
        Partner p1 = Mockito.mock(Partner.class), p2 = Mockito.mock(Partner.class);
        when(p1.getId()).thenReturn("p1"); when(p1.getName()).thenReturn("가 업체");
        when(p2.getId()).thenReturn("p2"); when(p2.getName()).thenReturn("나 업체");
        when(partners.findByProxyAffiliateId("a1")).thenReturn(List.of(p2, p1));

        mvc.perform(get("/api/v1/me").with(jwt()
                .jwt(j -> j.claim("resource_access", Map.of("backoffice-api", Map.of("roles", List.of("affiliate")))))
                .authorities(new SecurityConfig.KeycloakClientRoleConverter("backoffice-api"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("u1"))
            .andExpect(jsonPath("$.role").value("affiliate"))
            .andExpect(jsonPath("$.orgName").value("규슈연맹"))
            .andExpect(jsonPath("$.managedPartners[0].id").value("p1"))
            .andExpect(jsonPath("$.managedPartners[1].id").value("p2"));
    }
}
