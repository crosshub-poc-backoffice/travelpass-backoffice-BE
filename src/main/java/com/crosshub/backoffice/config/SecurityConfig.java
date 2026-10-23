package com.crosshub.backoffice.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Keycloak(crosshub realm)이 발급한 JWT를 검증하는 Resource Server 설정.
 *
 * role은 poc-backoffice의 client role로 두며, 이름은 서비스 DB(User.role)와 똑같이
 *   resource_access."poc-backoffice".roles = ["crosshub" | "affiliate" | "partner"]
 * → ROLE_CROSSHUB / ROLE_AFFILIATE / ROLE_PARTNER 로 매핑한다.
 *
 * 권한 판단 기준은 DB다. 여기 토큰 role은 "이 API를 부를 수 있나" 1차 거름망이고,
 * 업체 범위·crosshub 전용 여부는 CallerService(User 테이블)가 최종 판단한다.
 * (realm role이었다면 realm_access.roles 를 읽어야 한다.)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** role이 담긴 client id. 토큰의 aud/azp 검증에도 쓴다. */
    private static final String CLIENT_ID = "poc-backoffice";

    private static final String[] ALL_ROLES = {"CROSSHUB", "AFFILIATE", "PARTNER"};

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    /** dev 모드: 토큰 검증 없이 전부 허용 (Keycloak 붙이기 전 FE↔BE 연결 검증용). 기본은 꺼짐. */
    @Value("${app.dev.enabled:false}")
    private boolean devEnabled;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (devEnabled) {
            log.warn("##### app.dev.enabled=true — 토큰 검증 없이 모든 요청을 허용합니다. 로컬 개발 외에는 절대 켜지 마세요. #####");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // 컨트롤러가 던진 4xx/5xx를 BasicErrorController가 렌더링할 때 내부적으로 /error로
                // forward한다. 이것도 시큐리티 필터를 다시 타므로 denyAll에 걸리면 원래 상태코드(404 등)가
                // 403으로 덮인다 — 반드시 permitAll.
                .requestMatchers("/error").permitAll()
                // crosshub 전용: 계정 관리, 연맹 몫·정산 계좌 (서비스에서도 DB role로 한 번 더 확인)
                .requestMatchers("/api/v1/accounts", "/api/v1/accounts/**").hasRole("CROSSHUB")
                .requestMatchers("/api/v1/affiliate-shares").hasRole("CROSSHUB")
                // 그 외 업무 API는 세 role 모두. 업체 범위는 서비스 레이어에서 Caller(DB)로 건다.
                .requestMatchers("/api/v1/**").hasAnyRole(ALL_ROLES)
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * 서명·만료·issuer + "poc-backoffice용 토큰인가"(aud 또는 azp)까지 검증.
     * 같은 realm의 다른 client(backoffice-admin-api 등) 토큰은 여기서 401.
     * dev 모드에선 만들지 않는다 → Keycloak 없이도 뜬다.
     */
    @Bean
    @ConditionalOnProperty(name = "app.dev.enabled", havingValue = "false", matchIfMissing = true)
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new ClientAudienceValidator(CLIENT_ID)));
        return decoder;
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

    /**
     * aud에 clientId가 있거나(Keycloak Audience mapper를 단 경우), azp(토큰을 받아간 client)가 clientId면 통과.
     * Keycloak access token의 aud 기본값은 "account"라서 mapper 없이도 동작하도록 azp를 같이 본다.
     */
    public static class ClientAudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String clientId;

        public ClientAudienceValidator(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> aud = jwt.getAudience();
            boolean ok = (aud != null && aud.contains(clientId)) || clientId.equals(jwt.getClaimAsString("azp"));
            return ok ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                            "poc-backoffice용 토큰이 아닙니다 (aud/azp 불일치).", null));
        }
    }

    /** JWT의 resource_access.&lt;clientId&gt;.roles 를 ROLE_* 권한으로 변환. */
    public static class KeycloakClientRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

        private final String clientId;

        public KeycloakClientRoleConverter(String clientId) {
            this.clientId = clientId;
        }

        @Override
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
