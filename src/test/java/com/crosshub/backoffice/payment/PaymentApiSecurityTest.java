package com.crosshub.backoffice.payment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.config.SecurityConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 연맹 몫(/api/v1/affiliate-shares/**)은 URL 단계에서 크로스허브만 — 서비스까지 가기 전에 403 */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.dev.enabled=false", "app.cors.allowed-origins=http://localhost:3000"})
class PaymentApiSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean PaymentConfigService config;
    @MockBean PaymentCommandService commands;
    @MockBean CallerService callers;
    @MockBean JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String role) {
        return req.with(jwt()
                .jwt(j -> j.claim("resource_access", Map.of("backoffice-api", Map.of("roles", List.of(role)))))
                .authorities(new SecurityConfig.KeycloakClientRoleConverter("backoffice-api")));
    }

    @Test
    void 연맹몫은_업체_연맹_403() throws Exception {
        for (String role : List.of("partner", "affiliate")) {
            mvc.perform(as(get("/api/v1/affiliate-shares"), role)).andExpect(status().isForbidden());
            mvc.perform(as(put("/api/v1/affiliate-shares/affX"), role).contentType(MediaType.APPLICATION_JSON).content("{\"shareRate\":5}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void 토큰없으면_401() throws Exception {
        mvc.perform(put("/api/v1/pg-settings/JPY").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
