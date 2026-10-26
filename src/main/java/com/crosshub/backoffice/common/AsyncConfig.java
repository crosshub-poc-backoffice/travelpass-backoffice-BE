package com.crosshub.backoffice.common;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 백그라운드 작업(상품 번역·AI 검수)과 매일 도는 배치(@Scheduled)를 켠다.
 *
 * 번역·검수는 Solar(외부 AI) 호출이라 느리다. 상품 저장 응답은 바로 보내고 뒤에서 돈다 — FE 가 Next.js after() 로 하던 것을
 * BE 로 옮긴 것. 한 번에 2개까지만 돌리고 나머지는 줄을 선다 (Solar 호출 자체는 SolarClient 가 한 번에 하나씩만 보낸다).
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String BACKGROUND = "backgroundExecutor";

    @Bean(BACKGROUND)
    Executor backgroundExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("bg-");
        // 종료할 때 줄 선 작업은 버리되, 돌고 있는 작업은 잠깐 기다린다
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(20);
        ex.initialize();
        return ex;
    }
}
