package com.crosshub.backoffice.common;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * ResponseStatusException 공통 응답. server.error.include-message=never라 기본 에러 본문엔 사유가 안 실린다.
 * 사유가 코드 모양(roleMismatch, invalidStatus 등 영문 식별자)이면 {code}로 내보내 FE가 에러 키로 쓰게 하고,
 * 그 외(사람이 읽는 문장, id가 섞인 메시지)는 상태코드만 내보낸다 — 내부 정보가 응답에 새지 않게.
 * 도메인 예외(XxxException)는 각 컨트롤러의 @ExceptionHandler가 먼저 처리한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Pattern CODE = Pattern.compile("^[a-z][A-Za-z0-9]{1,40}$");

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException e) {
        String reason = e.getReason();
        Map<String, Object> body = reason != null && CODE.matcher(reason).matches()
                ? Map.of("code", reason)
                : Map.of("status", e.getStatusCode().value());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }
}
