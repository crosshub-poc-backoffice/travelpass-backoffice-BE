package com.crosshub.backoffice.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Keycloak 이 발급한 JWT 를 검증하는 Resource Server 설정. issuer(realm) 두 개를 믿는다.
 *
 *   직원 = crosshub realm → resource_access.backoffice-api.roles 를 ROLE_* 로, /api/v1/** 를 부른다
 *   고객 = idblock realm  → role 없음. ROLE_CUSTOMER 하나를 주고 /api/customer/** 만 부른다
 *
 * 토큰의 iss 를 보고 issuer 별 검증기(JwtIssuerAuthenticationManagerResolver)를 고른다. 목록에 없는 iss 는 401.
 * 두 realm 토큰 모두 aud 에 BE 이름표 backoffice-api 가 있어야 한다.
 * 검증기는 JWKS 주소를 직접 지정해 지연 생성한다 → BE 기동 때 Keycloak 이 떠 있지 않아도 된다 (첫 토큰 때 공개키를 받는다).
 *
 * role은 BE 이름표 client `backoffice-api`의 client role로 두며, 이름은 서비스 DB(User.role)와 똑같이
 *   resource_access."backoffice-api".roles = ["crosshub" | "affiliate" | "partner"]
 * 로그인 client(backoffice-web 등)는 따로이고, 그 client에 audience mapper·scope mapping 으로
 * backoffice-api 의 aud 와 role 이 토큰에 들어온다 (인증 서버 레포 keycloak-auth 의 realm JSON).
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


    private static final String[] ALL_ROLES = {"CROSSHUB", "AFFILIATE", "PARTNER"};

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    /** 인증 끄기: 토큰 검증 없이 전부 허용 (Keycloak 붙이기 전 FE↔BE 연결 검증용). 기본은 꺼짐. */
    @Value("${app.auth.disabled}")
    private boolean authDisabled;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    ObjectProvider<AuthenticationManagerResolver<HttpServletRequest>> resolver) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (authDisabled) {
            log.warn("##### app.auth.disabled=true — 토큰 검증 없이 모든 요청을 허용합니다. 로컬 개발 외에는 절대 켜지 마세요. #####");
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
                .requestMatchers("/api/v1/affiliate-shares", "/api/v1/affiliate-shares/**").hasRole("CROSSHUB")
                // crosshub 전용 쓰기·조회 (서비스에서도 DB role 로 한 번 더 막는다 — 여기는 서비스까지 가기 전 1차 거름망)
                //   정산 지급·지급 취소, 통화별 PG 설정 변경, 업체 활성·비활성, 변경요청 1건 조회·승인·반려, 로그인·접근·계정 변동 기록
                .requestMatchers(HttpMethod.POST, "/api/v1/settlements/pay", "/api/v1/settlements/unpay",
                        "/api/v1/settlements/affiliates/pay", "/api/v1/settlements/affiliates/unpay").hasRole("CROSSHUB")
                .requestMatchers(HttpMethod.PUT, "/api/v1/pg-settings/**").hasRole("CROSSHUB")
                .requestMatchers(HttpMethod.POST, "/api/v1/partners/*/toggle-active").hasRole("CROSSHUB")
                .requestMatchers("/api/v1/change-requests/*", "/api/v1/change-requests/*/*").hasRole("CROSSHUB")
                .requestMatchers("/api/v1/activity/logins", "/api/v1/activity/access", "/api/v1/activity/account-changes").hasRole("CROSSHUB")
                // 그 외 업무 API는 세 role 모두. 업체 범위는 서비스 레이어에서 Caller(DB)로 건다.
                // 로그인 기록: 직원 realm 토큰이면 백오피스 role 이 없어도(= 등록 안 된 계정의 실패 기록) 받는다. 고객 토큰은 컨트롤러가 403
                .requestMatchers(HttpMethod.POST, "/api/v1/logs/login").authenticated()
                .requestMatchers("/api/v1/**").hasAnyRole(ALL_ROLES)
                // 고객용 API: 고객 realm(idblock) 토큰만. 직원 토큰에는 ROLE_CUSTOMER 가 없어 403
                .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(resolver.getObject()));
        return http.build();
    }

    /**
     * issuer(realm) 별 검증기. 토큰의 iss 로 고른다 — 목록에 없는 iss 면 401.
     * 각 검증기: 서명(JWKS)·만료·issuer + aud 에 backoffice-api 가 있는지.
     *   직원 issuer → client role 을 ROLE_* 로 / 고객 issuer → ROLE_CUSTOMER
     * 인증을 끈 상태(app.auth.disabled=true)에선 만들지 않는다 → Keycloak 없이도 뜬다.
     */
    @Bean
    @ConditionalOnProperty(name = "app.auth.disabled", havingValue = "false", matchIfMissing = true)
    AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver(
            @Value("${app.keycloak.staff-issuer}") String staffIssuer,
            @Value("${app.keycloak.customer-issuer}") String customerIssuer,
            @Value("${app.keycloak.audience}") String audience) {
        Map<String, AuthenticationManager> byIssuer = new LinkedHashMap<>();
        byIssuer.put(staffIssuer, jwtAuthenticationManager(staffIssuer, audience, new KeycloakClientRoleConverter(audience)));
        if (customerIssuer != null && !customerIssuer.isBlank()) {
            byIssuer.put(customerIssuer, jwtAuthenticationManager(customerIssuer, audience, CUSTOMER_AUTHORITIES));
        }
        log.info("신뢰하는 issuer: {}", byIssuer.keySet());
        return new JwtIssuerAuthenticationManagerResolver(byIssuer::get);
    }

    /** 고객 토큰의 권한: role 은 없고 "고객이다" 하나. /api/customer/** 가 이걸 요구한다 */
    public static final Converter<Jwt, Collection<GrantedAuthority>> CUSTOMER_AUTHORITIES =
            jwt -> List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));

    private static AuthenticationManager jwtAuthenticationManager(String issuer, String audience,
                                                                  Converter<Jwt, Collection<GrantedAuthority>> authorities) {
        // Keycloak 의 JWKS 경로는 고정이다. issuer 로 discovery 를 받는 withIssuerLocation 은 빈 생성 시점에 HTTP 를 쳐서
        // Keycloak 이 먼저 떠 있어야 했다 — 주소를 직접 주면 첫 토큰 검증 때 받아온다.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new ClientAudienceValidator(audience)));
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return provider::authenticate;
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
     * aud 에 clientId(backoffice-api)가 있어야 통과. 로그인 client 쪽에 audience mapper 가 있어야 들어온다.
     * azp(토큰을 받아간 client)는 보지 않는다 — backoffice-api 는 토큰을 발급받는 client 가 아니므로
     * azp 가 backoffice-api 인 토큰은 존재하지 않고, 로그인 client 이름에 BE 를 묶지 않기 위해서다.
     */
    public static class ClientAudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String clientId;

        public ClientAudienceValidator(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> aud = jwt.getAudience();
            boolean ok = aud != null && aud.contains(clientId);
            return ok ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                            clientId + " 용 토큰이 아닙니다 (aud 에 없음).", null));
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
