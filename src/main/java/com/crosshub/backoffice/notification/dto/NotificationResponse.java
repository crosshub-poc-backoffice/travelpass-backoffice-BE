package com.crosshub.backoffice.notification.dto;

import com.crosshub.backoffice.notification.Notification;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.OffsetDateTime;

/** 저장형 알림 응답. payload는 원본 JSON 그대로 (텍스트/링크는 FE가 type+payload로 렌더). */
public record NotificationResponse(
        String id,
        String type,
        String sourceId,
        @JsonRawValue String payload,
        boolean read,
        OffsetDateTime createdAt) {

    public static NotificationResponse from(Notification n) {
        String pl = (n.getPayload() == null || n.getPayload().isBlank()) ? "null" : n.getPayload();
        return new NotificationResponse(n.getId(), n.getType(), n.getSourceId(), pl, n.isRead(), n.getCreatedAt());
    }
}
