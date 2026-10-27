package com.crosshub.backoffice.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 시작할 때 필요한 설정이 다 있는지 한 번에 확인한다. 코드·application.yml 에 기본값을 두지 않으므로
 * 빠진 값이 있으면 조용히 로컬 주소로 붙는 대신, 빠진 이름을 전부 적어서 멈춘다.
 *
 * 값은 환경변수(운영) 또는 .env(로컬)에서 온다 — 목록과 설명은 .env.example.
 * .env 를 읽은 뒤에 돌아야 하므로 가장 마지막 순서로 둔다 (META-INF/spring.factories 에 등록).
 */
public class RequiredEnv implements EnvironmentPostProcessor, Ordered {

    /** 빈 값이면 안 되는 것 */
    static final List<String> REQUIRED = List.of(
            "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD",
            "KEYCLOAK_STAFF_ISSUER_URI", "KEYCLOAK_AUDIENCE", "KEYCLOAK_SVC_CLIENT_ID", "KEYCLOAK_SVC_CLIENT_SECRET",
            "CORS_ALLOWED_ORIGINS", "APP_AUTH_DISABLED",
            "UPSTAGE_FAKE_WHEN_MISSING", "SOLAR_MIN_INTERVAL_MS",
            "S3_ENDPOINT", "S3_REGION", "S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_PUBLIC_URL");

    /** 있어야 하지만 빈 값이 뜻을 갖는 것 (빈 값 = 그 기능 끔·다른 키를 같이 씀) */
    static final List<String> PRESENT = List.of(
            "KEYCLOAK_CUSTOMER_ISSUER_URI", "UPSTAGE_API_KEY", "UPSTAGE_DOCS_API_KEY");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        List<String> problems = problems(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("설정이 빠졌습니다. .env(운영은 서버 환경변수)를 확인하세요 — 목록은 .env.example\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    static List<String> problems(ConfigurableEnvironment env) {
        List<String> out = new ArrayList<>();
        for (String name : REQUIRED) {
            String v = env.getProperty(name);
            if (v == null) out.add(name + " 없음");
            else if (v.isBlank()) out.add(name + " 빈 값");
        }
        for (String name : PRESENT) {
            if (env.getProperty(name) == null) out.add(name + " 없음 (쓰지 않으면 빈 값으로라도 적는다)");
        }
        // 가짜 번역을 끈 환경(운영·secure 프로필)에서 키가 비어 있으면 번역이 전부 실패한다
        if ("false".equalsIgnoreCase(env.getProperty("app.upstage.fake-when-missing"))
                && blank(env.getProperty("UPSTAGE_API_KEY"))) {
            out.add("UPSTAGE_API_KEY 빈 값 — 가짜 번역이 꺼져 있어(UPSTAGE_FAKE_WHEN_MISSING=false 또는 secure 프로필) 키가 필요하다");
        }
        return out;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
