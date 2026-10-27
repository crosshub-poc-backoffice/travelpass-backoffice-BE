package com.crosshub.backoffice.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosshub.backoffice.config.SecurityConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** /api/customer/me: 토큰 없음 401, 직원 토큰 403, 고객 토큰 200 */
@WebMvcTest(CustomerMeController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.auth.disabled=false", "app.cors.allowed-origins=http://localhost:3000"})
class CustomerMeApiSecurityTest {

    @Autowired MockMvc mvc;

    @Test
    void 토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/customer/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void 직원_토큰은_403() throws Exception {
        mvc.perform(get("/api/customer/me").with(jwt()
                .jwt(j -> j.claim("resource_access", Map.of("tp-office-partner-api", Map.of("roles", List.of("crosshub")))))
                .authorities(new SecurityConfig.KeycloakClientRoleConverter("tp-office-partner-api"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void 고객_토큰은_200_토큰_claim_반환() throws Exception {
        mvc.perform(get("/api/customer/me").with(jwt()
                .jwt(j -> j.subject("02c379b7-0000-0000-0000-000000000000")
                        .issuer("http://localhost:8080/realms/tp")
                        .claim("email", "test-customer@example.com").claim("email_verified", true).claim("name", "테스트 고객"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("02c379b7-0000-0000-0000-000000000000"))
            .andExpect(jsonPath("$.email").value("test-customer@example.com"))
            .andExpect(jsonPath("$.issuer").value("http://localhost:8080/realms/tp"));
    }

    @Test
    void 고객_토큰으로_직원_API는_403() throws Exception {
        mvc.perform(get("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andExpect(status().isForbidden());
    }
}
