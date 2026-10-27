package com.crosshub.backoffice.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosshub.backoffice.config.SecurityConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 로그 기록 API: 토큰 없음 401, 고객 토큰 403, 등록 안 된 직원 계정의 실패 기록은 받는다, 접근 기록은 호출자 본인으로 */
@WebMvcTest(LogController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.auth.disabled=false", "app.cors.allowed-origins=http://localhost:3000"})
class LogApiTest {

    @Autowired MockMvc mvc;
    @MockBean LoginLogRepository loginLogs;
    @MockBean AccessLogRepository accessLogs;
    @MockBean UserAccountRepository users;
    @MockBean CallerService callers;

    @Test
    void 토큰_없으면_401() throws Exception {
        mvc.perform(post("/api/v1/logs/login").contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"login\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 고객_토큰은_403() throws Exception {
        mvc.perform(post("/api/v1/logs/login").with(jwt().jwt(j -> j.claim("preferred_username", "c@example.com"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"login\"}"))
                .andExpect(status().isForbidden());
        verify(loginLogs, never()).save(any());
    }

    @Test
    void 등록_안된_계정의_실패도_토큰의_이름으로_남긴다() throws Exception {
        when(users.findByLoginId("ghost")).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/logs/login").with(jwt().jwt(j -> j.claim("preferred_username", "ghost")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"failed\",\"failReason\":\"unknownId\",\"ip\":\"1.2.3.4\",\"userAgent\":\"UA\"}"))
                .andExpect(status().isNoContent());
        ArgumentCaptor<LoginLog> saved = ArgumentCaptor.forClass(LoginLog.class);
        verify(loginLogs).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getIp()).isEqualTo("1.2.3.4");
    }

    @Test
    void 이벤트가_이상하면_400() throws Exception {
        mvc.perform(post("/api/v1/logs/login").with(jwt().jwt(j -> j.claim("preferred_username", "u")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"hack\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 접근기록은_직원_role이_있어야_하고_호출자_본인으로() throws Exception {
        mvc.perform(post("/api/v1/logs/access").with(jwt().jwt(j -> j.claim("preferred_username", "u")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"entries\":[{\"menu\":\"products\",\"kind\":\"list\"}]}"))
                .andExpect(status().isForbidden());

        when(callers.current()).thenReturn(new Caller("u-1", "u", "partner", "p1", null, List.of("p1")));
        mvc.perform(post("/api/v1/logs/access").with(jwt().jwt(j -> j.claim("preferred_username", "u"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PARTNER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"entries\":[{\"menu\":\"products\",\"kind\":\"list\"},{\"menu\":\"x\",\"kind\":\"update\",\"path\":\"/ko/x\"}]}"))
                .andExpect(status().isNoContent());
        verify(accessLogs, org.mockito.Mockito.times(2)).save(any());

        mvc.perform(post("/api/v1/logs/access").with(jwt().jwt(j -> j.claim("preferred_username", "u"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PARTNER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"entries\":[{\"menu\":\"products\",\"kind\":\"drop\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
