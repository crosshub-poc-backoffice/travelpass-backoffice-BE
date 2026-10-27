package com.crosshub.backoffice.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.partner.PartnerCommandService;
import com.crosshub.backoffice.partner.PartnerController;
import com.crosshub.backoffice.partner.PartnerService;
import com.crosshub.backoffice.payment.PaymentCommandService;
import com.crosshub.backoffice.payment.PaymentConfigService;
import com.crosshub.backoffice.payment.PaymentController;
import com.crosshub.backoffice.product.change.ChangeRequestController;
import com.crosshub.backoffice.product.change.ChangeRequestService;
import com.crosshub.backoffice.report.ActivityLogService;
import com.crosshub.backoffice.report.ReportController;
import com.crosshub.backoffice.report.StatsService;
import com.crosshub.backoffice.settlement.SettlementController;
import com.crosshub.backoffice.settlement.SettlementService;
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

/**
 * 크로스허브 전용 동작은 URL 단계에서 업체·연맹 토큰을 403 으로 막는다 (서비스까지 안 감).
 * 같은 컨트롤러의 다른 동작(정산 조회·PG 설정 조회·업체 수정·변경요청 생성·작업 이력)은 막지 않는다.
 */
@WebMvcTest({SettlementController.class, PaymentController.class, PartnerController.class, ChangeRequestController.class, ReportController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.auth.disabled=false", "app.cors.allowed-origins=http://localhost:3000"})
class CrosshubOnlyUrlTest {

    @Autowired MockMvc mvc;
    @MockBean CallerService callers;
    @MockBean SettlementService settlements;
    @MockBean PaymentConfigService paymentConfig;
    @MockBean PaymentCommandService paymentCommands;
    @MockBean PartnerService partners;
    @MockBean PartnerCommandService partnerCommands;
    @MockBean ChangeRequestService changeRequests;
    @MockBean ActivityLogService logs;
    @MockBean StatsService stats;
    @MockBean JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String role) {
        return req.with(jwt()
                .jwt(j -> j.claim("resource_access", Map.of("backoffice-api", Map.of("roles", List.of(role)))))
                .authorities(new SecurityConfig.KeycloakClientRoleConverter("backoffice-api")))
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    static final List<MockHttpServletRequestBuilder> CROSSHUB_ONLY() {
        return List.of(
                post("/api/v1/settlements/pay"), post("/api/v1/settlements/unpay"),
                post("/api/v1/settlements/affiliates/pay"), post("/api/v1/settlements/affiliates/unpay"),
                put("/api/v1/pg-settings/JPY"),
                post("/api/v1/partners/p1/toggle-active"),
                get("/api/v1/change-requests/r1"), post("/api/v1/change-requests/r1/approve"), post("/api/v1/change-requests/r1/reject"),
                get("/api/v1/activity/logins?from=2026-10-01&to=2026-10-02"),
                get("/api/v1/activity/access?from=2026-10-01&to=2026-10-02"),
                get("/api/v1/activity/account-changes?from=2026-10-01&to=2026-10-02"));
    }

    @Test
    void 업체_연맹은_크로스허브_전용_URL에서_403() throws Exception {
        for (String role : List.of("partner", "affiliate")) {
            for (MockHttpServletRequestBuilder req : CROSSHUB_ONLY()) {
                mvc.perform(as(req, role)).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void 크로스허브는_통과() throws Exception {
        for (MockHttpServletRequestBuilder req : CROSSHUB_ONLY()) {
            // 서비스는 mock 이라 2xx 가 나온다 — 403 이 아니면 URL 규칙을 통과한 것
            mvc.perform(as(req, "crosshub")).andExpect(result -> {
                int s = result.getResponse().getStatus();
                if (s == 401 || s == 403) throw new AssertionError(result.getRequest().getRequestURI() + " → " + s);
            });
        }
    }

    @Test
    void 같은_컨트롤러의_다른_동작은_업체도_통과() throws Exception {
        List<MockHttpServletRequestBuilder> open = List.of(
                get("/api/v1/settlements?month=2026-10"), get("/api/v1/pg-settings"),
                put("/api/v1/partners/p1"), post("/api/v1/products/p1/change-requests"),
                get("/api/v1/change-requests?limit=5"), get("/api/v1/activity/audit?from=2026-10-01&to=2026-10-02"));
        for (MockHttpServletRequestBuilder req : open) {
            mvc.perform(as(req, "partner")).andExpect(result -> {
                int s = result.getResponse().getStatus();
                if (s == 401 || s == 403) throw new AssertionError(result.getRequest().getRequestURI() + " → " + s);
            });
        }
    }
}
