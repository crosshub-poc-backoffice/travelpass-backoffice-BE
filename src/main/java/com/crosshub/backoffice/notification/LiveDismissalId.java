package com.crosshub.backoffice.notification;

import java.io.Serializable;
import java.util.Objects;

/** LiveNotificationDismissal 복합키 (userId, key). */
public class LiveDismissalId implements Serializable {
    private String userId;
    private String key;

    public LiveDismissalId() {
    }

    public LiveDismissalId(String userId, String key) {
        this.userId = userId;
        this.key = key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LiveDismissalId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, key);
    }
}
