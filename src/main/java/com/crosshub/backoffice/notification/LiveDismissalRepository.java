package com.crosshub.backoffice.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveDismissalRepository extends JpaRepository<LiveNotificationDismissal, LiveDismissalId> {
    boolean existsByUserIdAndKey(String userId, String key);
    java.util.List<LiveNotificationDismissal> findByUserId(String userId);
}
