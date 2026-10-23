package com.crosshub.backoffice.account.keycloak;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Keycloak Admin API 클라이언트 (계정 발급·비번 초기화·활성 토글).
 * backoffice-admin-api(service account, client credentials)로 호출한다.
 * 권한: realm-management manage-users + query-clients, 그리고 fine-grained로 poc-backoffice에만 view·map-roles
 * (infra/keycloak/realm/crosshub-realm.json). secret은 BE에만 둔다.
 */
@Component
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    private final RestClient http = RestClient.create();
    private final String issuer;
    private final String adminBase;
    private final String appClientId;
    private final String adminClientId;
    private final String adminClientSecret;

    private String cachedToken;
    private Instant cachedTokenExpiresAt = Instant.EPOCH;

    public KeycloakAdminClient(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${app.keycloak.app-client-id:poc-backoffice}") String appClientId,
            @Value("${app.keycloak.admin-client-id:backoffice-admin-api}") String adminClientId,
            @Value("${app.keycloak.admin-client-secret:}") String adminClientSecret) {
        this.issuer = issuer;
        this.adminBase = issuer.replace("/realms/", "/admin/realms/");
        this.appClientId = appClientId;
        this.adminClientId = adminClientId;
        this.adminClientSecret = adminClientSecret;
    }

    public boolean enabled() {
        return adminClientSecret != null && !adminClientSecret.isBlank();
    }

    /** username(= User.loginId)이 Keycloak에 이미 있나 */
    public boolean userExists(String username) {
        return findUserId(username) != null;
    }

    /** 임시 비번 + 첫 로그인 비번 변경 강제 + poc-backoffice client role(= DB role)로 유저 생성. Keycloak user id 반환 */
    public String createUser(String username, String name, String role, String tempPassword) {
        var res = call(() -> http.post().uri(adminBase + "/users")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username", username,
                        "firstName", name,
                        "enabled", true,
                        "requiredActions", List.of("UPDATE_PASSWORD"),
                        "credentials", List.of(Map.of("type", "password", "value", tempPassword, "temporary", true))))
                .retrieve().toBodilessEntity());
        String location = res.getHeaders().getLocation() != null ? res.getHeaders().getLocation().toString() : null;
        String id = location != null ? location.substring(location.lastIndexOf('/') + 1) : findUserId(username);
        if (id == null) throw new KeycloakSyncException("생성된 Keycloak 유저 id를 못 찾음: " + username);
        try {
            assignClientRole(id, role);
        } catch (RuntimeException e) {
            deleteUser(id); // role 없는 반쪽 계정이 남지 않게
            throw e;
        }
        return id;
    }

    public void deleteUser(String keycloakUserId) {
        try {
            http.delete().uri(adminBase + "/users/" + keycloakUserId).headers(h -> h.setBearerAuth(token()))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Keycloak 유저 롤백 삭제 실패: {}", keycloakUserId, e);
        }
    }

    /** 임시 비번으로 초기화 + 첫 로그인 비번 변경 강제 + Keycloak 세션 종료. Keycloak에 없던 계정이면 새로 만든다 */
    public void resetPassword(String username, String name, String role, String tempPassword) {
        String id = findUserId(username);
        if (id == null) {
            createUser(username, name, role, tempPassword);
            return;
        }
        call(() -> http.put().uri(adminBase + "/users/" + id + "/reset-password")
                .headers(h -> h.setBearerAuth(token())).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", tempPassword, "temporary", true))
                .retrieve().toBodilessEntity());
        call(() -> http.put().uri(adminBase + "/users/" + id)
                .headers(h -> h.setBearerAuth(token())).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requiredActions", List.of("UPDATE_PASSWORD")))
                .retrieve().toBodilessEntity());
        logout(id);
    }

    /** 활성/비활성. 비활성화하면 Keycloak 세션도 종료. Keycloak에 없는 계정이면 아무것도 안 한다 */
    public void setEnabled(String username, boolean enabled) {
        String id = findUserId(username);
        if (id == null) return;
        call(() -> http.put().uri(adminBase + "/users/" + id)
                .headers(h -> h.setBearerAuth(token())).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabled))
                .retrieve().toBodilessEntity());
        if (!enabled) logout(id);
    }

    // ---- 내부 ----

    private void logout(String id) {
        call(() -> http.post().uri(adminBase + "/users/" + id + "/logout")
                .headers(h -> h.setBearerAuth(token())).retrieve().toBodilessEntity());
    }

    @SuppressWarnings("unchecked")
    private String findUserId(String username) {
        List<Map<String, Object>> rows = call(() -> http.get()
                .uri(adminBase + "/users?exact=true&username={u}", username)
                .headers(h -> h.setBearerAuth(token())).retrieve().body(List.class));
        return rows == null || rows.isEmpty() ? null : (String) rows.get(0).get("id");
    }

    @SuppressWarnings("unchecked")
    private void assignClientRole(String userId, String role) {
        List<Map<String, Object>> clients = call(() -> http.get()
                .uri(adminBase + "/clients?clientId={c}", appClientId)
                .headers(h -> h.setBearerAuth(token())).retrieve().body(List.class));
        if (clients == null || clients.isEmpty()) throw new KeycloakSyncException("client 없음: " + appClientId);
        String clientUuid = (String) clients.get(0).get("id");
        Map<String, Object> roleRep;
        try {
            roleRep = http.get().uri(adminBase + "/clients/" + clientUuid + "/roles/" + role)
                    .headers(h -> h.setBearerAuth(token())).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new KeycloakSyncException("client role 없음: " + role + " (Keycloak에 crosshub/affiliate/partner role 필요)", e);
        }
        call(() -> http.post().uri(adminBase + "/users/" + userId + "/role-mappings/clients/" + clientUuid)
                .headers(h -> h.setBearerAuth(token())).contentType(MediaType.APPLICATION_JSON)
                .body(List.of(roleRep)).retrieve().toBodilessEntity());
    }

    @SuppressWarnings("unchecked")
    private synchronized String token() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt.minusSeconds(10))) return cachedToken;
        if (!enabled()) throw new KeycloakSyncException("KEYCLOAK_ADMIN_CLIENT_SECRET 미설정");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", adminClientId);
        form.add("client_secret", adminClientSecret);
        Map<String, Object> res = call(() -> http.post().uri(issuer + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class));
        cachedToken = (String) res.get("access_token");
        cachedTokenExpiresAt = Instant.now().plusSeconds(((Number) res.get("expires_in")).longValue());
        return cachedToken;
    }

    private static <T> T call(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (KeycloakSyncException e) {
            throw e;
        } catch (RestClientException e) {
            throw new KeycloakSyncException("Keycloak Admin API 호출 실패: " + e.getMessage(), e);
        }
    }

    /** Keycloak 반영 실패. 호출 쪽은 DB를 바꾸지 않는다 */
    public static class KeycloakSyncException extends RuntimeException {
        public KeycloakSyncException(String m) { super(m); }
        public KeycloakSyncException(String m, Throwable c) { super(m, c); }
    }

    @SuppressWarnings("unused")
    private static boolean is4xx(HttpStatusCode c) { return c.is4xxClientError(); }
}
