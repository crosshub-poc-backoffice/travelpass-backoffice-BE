package com.crosshub.backoffice.account.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 계정 발급 때 넣는 그룹 경로가 realm JSON(keycloak-auth realm/tp-office-realm.json)의 그룹과 같아야 한다 */
class KeycloakGroupPathTest {

    @Test
    void role마다_merchant_그룹() {
        assertThat(KeycloakAdminClient.groupPath("crosshub")).isEqualTo("/merchant-crosshub");
        assertThat(KeycloakAdminClient.groupPath("affiliate")).isEqualTo("/merchant-affiliate");
        assertThat(KeycloakAdminClient.groupPath("partner")).isEqualTo("/merchant-partner");
    }
}
