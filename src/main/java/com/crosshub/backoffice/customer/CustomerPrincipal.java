package com.crosshub.backoffice.customer;

import org.springframework.security.oauth2.jwt.Jwt;

/** 고객 토큰(tp realm)에서 읽은 "누구인가". id = sub (Keycloak user id). 예약·문의의 customerId 가 된다 */
public record CustomerPrincipal(String id, String name, String email) {

    public static CustomerPrincipal from(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = jwt.getClaimAsString("preferred_username");
        if (name == null || name.isBlank()) name = jwt.getClaimAsString("email");
        return new CustomerPrincipal(jwt.getSubject(), name, jwt.getClaimAsString("email"));
    }
}
