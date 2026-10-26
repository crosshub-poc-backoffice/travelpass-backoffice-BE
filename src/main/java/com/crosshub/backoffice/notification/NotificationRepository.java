package com.crosshub.backoffice.notification;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    /** 보존기간 내 내 알림. 안 읽은 것 먼저, 그다음 최신순. */
    List<Notification> findByUserIdAndCreatedAtGreaterThanEqualOrderByReadAscCreatedAtDesc(
            String userId, OffsetDateTime cutoff);

    long countByUserIdAndReadFalse(String userId);

    boolean existsByUserIdAndTypeAndSourceId(String userId, String type, String sourceId);

    /** 보존 기간이 지난 알림 삭제 (NotificationCleanupJob) */
    @Modifying
    @Query("delete from Notification n where n.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") String userId);
}
