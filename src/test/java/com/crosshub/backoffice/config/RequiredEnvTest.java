package com.crosshub.backoffice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequiredEnvTest {

    private static MockEnvironment full() {
        MockEnvironment env = new MockEnvironment();
        RequiredEnv.REQUIRED.forEach(n -> env.setProperty(n, "x"));
        RequiredEnv.PRESENT.forEach(n -> env.setProperty(n, ""));
        return env;
    }

    @Test
    void 다_있으면_통과() {
        assertThat(RequiredEnv.problems(full())).isEmpty();
    }

    @Test
    void 빠진_값을_한번에_전부_알려준다() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DB_HOST", " ");
        var problems = RequiredEnv.problems(env);
        assertThat(problems).contains("DB_HOST 빈 값", "DB_PASSWORD 없음", "S3_PUBLIC_URL 없음");
        assertThat(problems).anyMatch(p -> p.startsWith("UPSTAGE_API_KEY 없음"));
        assertThat(problems).hasSize(RequiredEnv.REQUIRED.size() + RequiredEnv.PRESENT.size());
        assertThatThrownBy(() -> new RequiredEnv().postProcessEnvironment(env, null))
                .hasMessageContaining("DB_HOST 빈 값").hasMessageContaining("S3_PUBLIC_URL 없음");
    }

    @Test
    void 가짜_번역을_끄면_키가_있어야_한다() {
        MockEnvironment env = full();
        env.setProperty("app.upstage.fake-when-missing", "false");
        assertThat(RequiredEnv.problems(env)).anyMatch(p -> p.startsWith("UPSTAGE_API_KEY 빈 값"));
        env.setProperty("UPSTAGE_API_KEY", "up_xxx");
        assertThat(RequiredEnv.problems(env)).isEmpty();
    }
}
