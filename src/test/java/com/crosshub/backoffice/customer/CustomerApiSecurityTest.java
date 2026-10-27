package com.crosshub.backoffice.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosshub.backoffice.booking.CustomerBookingService;
import com.crosshub.backoffice.config.SecurityConfig;
import com.crosshub.backoffice.customer.dto.CustomerDtos.BookingView;
import com.crosshub.backoffice.inquiry.CustomerInquiryService;
import com.crosshub.backoffice.product.ProductRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** /api/customer/**: 토큰 없음 401, 직원 토큰 403, 고객 토큰은 통과 + 토큰 sub 가 customerId 로 전달 */
@WebMvcTest(CustomerApiController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.auth.disabled=false", "app.cors.allowed-origins=http://localhost:3000"})
class CustomerApiSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean CustomerBookingService bookings;
    @MockBean CustomerInquiryService inquiries;
    @MockBean ProductRepository products;

    private static MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder req) {
        return req.with(jwt().jwt(j -> j.subject("cust-1").claim("name", "테스트 고객").claim("email", "c@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    @Test
    void 토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/customer/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customer/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void 직원_토큰은_403() throws Exception {
        mvc.perform(get("/api/customer/bookings").with(jwt()
                .jwt(j -> j.claim("resource_access", Map.of("tp-office-partner-api", Map.of("roles", List.of("crosshub")))))
                .authorities(new SecurityConfig.KeycloakClientRoleConverter("tp-office-partner-api"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void 고객_토큰_내_예약_목록() throws Exception {
        when(bookings.list(any())).thenReturn(List.of(new BookingView("b1", "p1", "상품", "업체", "JPY",
                LocalDate.of(2026, 11, 3), 2, 9000, null, "confirmed", "instant", OffsetDateTime.now(),
                null, null, null, null, "null")));
        mvc.perform(asCustomer(get("/api/customer/bookings")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("b1"))
            .andExpect(jsonPath("$[0].status").value("confirmed"));
    }

    @Test
    void 예약_신청은_201() throws Exception {
        when(bookings.create(any(), any())).thenReturn(new BookingView("b2", "p1", "상품", "업체", "JPY",
                LocalDate.of(2026, 11, 3), 1, 4500, null, "confirmed", "instant", OffsetDateTime.now(),
                null, null, null, null, "null"));
        mvc.perform(asCustomer(post("/api/customer/bookings")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"p1\",\"nationality\":\"KR\",\"useDate\":\"2026-11-03\",\"people\":1,\"amount\":4500,\"idempotencyKey\":\"k1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("b2"));
    }
}
