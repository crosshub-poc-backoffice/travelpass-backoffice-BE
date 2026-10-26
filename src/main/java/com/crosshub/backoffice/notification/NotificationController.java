package com.crosshub.backoffice.notification;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.notification.dto.NotificationResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 API (저장형). 라이브 목록은 FE가 도메인 API로 계산.
 *  GET  /api/v1/notifications            내 이벤트 알림(보존기간 내)
 *  GET  /api/v1/notifications/count      안 읽은 수
 *  POST /api/v1/notifications/{id}/read  하나 읽음
 *  POST /api/v1/notifications/read-all   전부 읽음
 *  POST /api/v1/notifications/live-dismiss  { "key": "booking-xxx" } 라이브 확인표시
 *  GET  /api/v1/notifications/live-dismissals  내가 확인표시한 라이브 알림 키 목록
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;
    private final CallerService callers;

    public NotificationController(NotificationService service, CallerService callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping
    public List<NotificationResponse> list() {
        return service.list(callers.current());
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("unread", service.unreadCount(callers.current()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id) {
        service.markRead(callers.current(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() {
        return Map.of("updated", service.markAllRead(callers.current()));
    }

    @GetMapping("/live-dismissals")
    public List<String> liveDismissals() {
        return service.liveDismissals(callers.current());
    }

    @PostMapping("/live-dismiss")
    public ResponseEntity<Void> dismiss(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        if (key != null && !key.isBlank()) {
            service.dismissLive(callers.current(), key);
        }
        return ResponseEntity.noContent().build();
    }
}
