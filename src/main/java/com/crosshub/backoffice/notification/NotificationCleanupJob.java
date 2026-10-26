package com.crosshub.backoffice.notification;

import com.crosshub.backoffice.account.AccessLogRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일 03:00 JST: 보존 기간이 지난 알림(7일)과 접근 기록(30일)을 지운다.
 * FE vercel.json 의 cron /api/cron/notifications-cleanup(18:00 UTC) 을 옮긴 것.
 */
@Component
public class NotificationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationCleanupJob.class);
    public static final int ACCESS_LOG_RETENTION_DAYS = 30;

    private final NotificationRepository notifications;
    private final AccessLogRepository accessLogs;

    public NotificationCleanupJob(NotificationRepository notifications, AccessLogRepository accessLogs) {
        this.notifications = notifications;
        this.accessLogs = accessLogs;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int deleted = notifications.deleteByCreatedAtBefore(now.minusDays(NotificationService.RETENTION_DAYS));
        int access = accessLogs.deleteByCreatedAtBefore(now.minusDays(ACCESS_LOG_RETENTION_DAYS));
        log.info("[cleanup] 알림 {}건, 접근 기록 {}건 삭제", deleted, access);
    }
}
