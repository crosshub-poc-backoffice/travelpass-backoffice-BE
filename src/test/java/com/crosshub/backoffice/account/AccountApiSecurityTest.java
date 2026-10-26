package com.crosshub.backoffice.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosshub.backoffice.config.SecurityConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** /api/v1/accounts: 토큰 없음 401, partner 403, crosshub 200, 에러 코드 응답 */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.dev.enabled=false", "app.cors.allowed-origins=http://localhost:3000"})
class AccountApiSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean AccountService accounts;
    @MockBean AccountCommandService commands;
    @MockBean CallerService callers;
    @MockBean JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String role) {
        return req.with(jwt()
                .jwt(j -> j.claim("resource_access", Map.of("backoffice-api", Map.of("roles", List.of(role)))))
                .authorities(new SecurityConfig.KeycloakClientRoleConverter("backoffice-api")));
    }

    @Test
    void 토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void partner는_403() throws Exception {
        mvc.perform(as(get("/api/v1/accounts"), "partner")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/accounts/u1/toggle-active"), "partner")).andExpect(status().isForbidden());
    }

    @Test
    void affiliate는_403() throws Exception {
        mvc.perform(as(get("/api/v1/accounts"), "affiliate")).andExpect(status().isForbidden());
    }

    @Test
    void crosshub는_200() throws Exception {
        when(callers.requireCrosshub()).thenReturn(new Caller("u-admin", "test-crosshub", "crosshub", null, null, List.of()));
        mvc.perform(as(get("/api/v1/accounts"), "crosshub")).andExpect(status().isOk());
    }

    @Test
    void 발급_실패는_code로_응답() throws Exception {
        when(callers.requireCrosshub()).thenReturn(new Caller("u-admin", "test-crosshub", "crosshub", null, null, List.of()));
        when(commands.issue(any(), any())).thenThrow(new AccountException(HttpStatus.CONFLICT, "loginIdTaken"));
        mvc.perform(as(post("/api/v1/accounts"), "crosshub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"partner\",\"loginId\":\"dup-id\",\"partnerId\":\"p1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("loginIdTaken"));
    }
}
