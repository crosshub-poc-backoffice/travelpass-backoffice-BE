package com.crosshub.backoffice.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** v1 "LiveNotificationDismissal" — 라이브 알림을 "봤다"고 표시(사용자별). key는 "inquiry-<id>" 형식. */
@Entity
@Table(name = "LiveNotificationDismissal")
@IdClass(LiveDismissalId.class)
public class LiveNotificationDismissal {

    @Id
    @Column(name = "userId")
    private String userId;

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    protected LiveNotificationDismissal() {
    }

    public LiveNotificationDismissal(String userId, String key, OffsetDateTime createdAt) {
        this.userId = userId;
        this.key = key;
        this.createdAt = createdAt;
    }

    public String getUserId() { return userId; }
    public String getKey() { return key; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
