package com.crosshub.backoffice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeycloakUrlsTest {

    @Test
    void 접속_주소가_비면_issuer_그대로() {
        assertThat(KeycloakUrls.forNetwork("http://localhost:8080/realms/tp-office", ""))
                .isEqualTo("http://localhost:8080/realms/tp-office");
    }

    @Test
    void 접속_주소가_있으면_scheme_host_port만_바꾼다() {
        assertThat(KeycloakUrls.forNetwork("http://localhost:8080/realms/tp-office", "http://host.docker.internal:8080/"))
                .isEqualTo("http://host.docker.internal:8080/realms/tp-office");
    }
}
