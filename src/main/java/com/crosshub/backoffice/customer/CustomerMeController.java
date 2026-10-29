package com.crosshub.backoffice.customer;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객용 API 의 첫 엔드포인트. 고객 realm(tp) 토큰으로만 부를 수 있다 (SecurityConfig: /api/customer/** → ROLE_CUSTOMER).
 *
 * 고객은 DB 에 아직 없다 — 토큰의 claim 만 돌려준다. 문의를 고객에 연결할 때(V2) 토큰 sub 를 고객 키로 쓴다.
 * 이메일은 바뀔 수 있으니 식별자는 sub 다.
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerMeController {

    /** 토큰에서 읽은 고객 정보 */
    public record CustomerMe(String id, String email, Boolean emailVerified, String name, String issuer) {}

    @GetMapping("/me")
    public CustomerMe me(@AuthenticationPrincipal Jwt jwt) {
        return new CustomerMe(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsBoolean("email_verified"),
                jwt.getClaimAsString("name"),
                jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
    }
}
