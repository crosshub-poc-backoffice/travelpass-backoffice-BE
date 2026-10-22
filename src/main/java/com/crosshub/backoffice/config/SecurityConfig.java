package com.crosshub.backoffice.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Keycloak(crosshub realm)이 발급한 JWT를 검증하는 Resource Server 설정.
 *
 * crosshub는 role을 poc-backoffice의 "client role"로 두었으므로, 토큰에서는
 *   resource_access."poc-backoffice".roles = ["partner" | "ops" | "admin"]
 * 위치에 담긴다. 이 값을 ROLE_PARTNER / ROLE_OPS / ROLE_ADMIN 권한으로 매핑한다.
 * (realm role이었다면 realm_access.roles 를 읽어야 한다 — 그 경우 아래 CLIENT_ID 분기를 지우면 된다.)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** role이 담긴 client id. crosshub에서 만든 poc-backoffice 클라이언트. */
    private static final String CLIENT_ID = "poc-backoffice";

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    /** dev 모드: 토큰 검증 없이 전부 허용 (Keycloak 붙이기 전 FE↔BE 연결 검증용). */
    @Value("${app.dev.enabled:false}")
    private boolean devEnabled;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (devEnabled) {
            // 개발 편의: 인증 없이 전부 허용. oauth2(=Keycloak) 설정을 걸지 않으므로 Keycloak 없이도 뜬다.
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // 조회: partner/ops/admin 모두
                .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/*")
                    .hasAnyRole("PARTNER", "OPS", "ADMIN")
                // 생성·수정: partner(본인 업체)·admin. 업체 범위 체크는 서비스 레이어에서 sub/DB로.
                .requestMatchers(HttpMethod.POST, "/api/v1/products").hasAnyRole("PARTNER", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/*").hasAnyRole("PARTNER", "ADMIN")
                // 예약 조회: partner/ops/admin (partner는 본인 업체만 — 서비스 레이어에서 범위)
                .requestMatchers(HttpMethod.GET, "/api/v1/bookings", "/api/v1/bookings/*")
                    .hasAnyRole("PARTNER", "OPS", "ADMIN")
                // 문의 조회
                .requestMatchers(HttpMethod.GET, "/api/v1/inquiries", "/api/v1/inquiries/*")
                    .hasAnyRole("PARTNER", "OPS", "ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakClientRoleConverter(CLIENT_ID));
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    /** JWT의 resource_access.&lt;clientId&gt;.roles 를 ROLE_* 권한으로 변환. */
    static class KeycloakClientRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

        private final String clientId;

        KeycloakClientRoleConverter(String clientId) {
            this.clientId = clientId;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Object resourceAccess = jwt.getClaim("resource_access");
            if (!(resourceAccess instanceof Map<?, ?> ra) || !(ra.get(clientId) instanceof Map<?, ?> client)
                    || !(client.get("roles") instanceof Collection<?> roles)) {
                return List.of();
            }
            return roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + String.valueOf(r).toUpperCase()))
                .toList();
        }
    }
}
