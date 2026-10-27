package com.crosshub.backoffice.config;

import java.net.URI;

/**
 * BE 가 Keycloak 에 직접 접속할 주소. 토큰의 iss 검사는 issuer 그대로 하고, 접속(서명 키·계정 발급 API)만 이 주소로 한다.
 *
 * 브라우저가 받은 토큰의 iss 는 http://localhost:8080/realms/… 인데, BE 를 컨테이너로 띄우면 그 안의 localhost 는 컨테이너 자신이라
 * Keycloak 에 닿지 않는다. 그때 compose.yaml 이 KEYCLOAK_INTERNAL_URL=http://host.docker.internal:8080 으로 접속 주소만 바꾼다.
 * 그 외(맥에서 bootRun·운영처럼 모두가 같은 주소로 닿을 때)엔 값이 없고 issuer 주소로 접속한다. .env 에 적지 않는다.
 */
public final class KeycloakUrls {

    private KeycloakUrls() {}

    /** issuer 의 scheme://host:port 만 internalBase 로 바꾼 주소. internalBase 가 비면 issuer 그대로 */
    public static String forNetwork(String issuer, String internalBase) {
        if (internalBase == null || internalBase.isBlank()) return issuer;
        URI i = URI.create(issuer);
        String base = internalBase.replaceAll("/$", "");
        return base + i.getRawPath();
    }
}
