package com.crosshub.backoffice.notification;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.notification.dto.NotificationResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장형(이벤트) 알림. 사용자(Caller.userId) 기준.
 * 라이브(실시간 계산) 알림은 FE가 bookings/inquiries/products API로 계산한다 (이번 패스 범위 밖).
 */
@Service
public class NotificationService {

    public static final int RETENTION_DAYS = 7;

    private final NotificationRepository repository;
    private final LiveDismissalRepository dismissals;

    public NotificationService(NotificationRepository repository, LiveDismissalRepository dismissals) {
        this.repository = repository;
        this.dismissals = dismissals;
    }

    private static OffsetDateTime cutoff() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(Caller caller) {
        return repository
                .findByUserIdAndCreatedAtGreaterThanEqualOrderByReadAscCreatedAtDesc(caller.userId(), cutoff())
                .stream().map(NotificationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(Caller caller) {
        return repository.countByUserIdAndReadFalse(caller.userId());
    }

    /** 특정 알림 읽음. 내 것만. */
    @Transactional
    public void markRead(Caller caller, String id) {
        repository.findById(id)
                .filter(n -> n.getUserId().equals(caller.userId()))
                .ifPresent(n -> n.setRead(true));
    }

    @Transactional
    public int markAllRead(Caller caller) {
        return repository.markAllRead(caller.userId());
    }

    /** 라이브 알림 "봤음" 표시. 멱등(이미 있으면 그대로). */
    @Transactional
    public void dismissLive(Caller caller, String key) {
        if (!dismissals.existsByUserIdAndKey(caller.userId(), key)) {
            dismissals.save(new LiveNotificationDismissal(
                    caller.userId(), key, OffsetDateTime.now(ZoneOffset.UTC)));
        }
    }
}
