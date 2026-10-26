package com.crosshub.backoffice.ai;

/** Solar(Upstage) 호출 실패. 메시지는 사람이 읽는 문장 — 응답 본문에는 내보내지 않고 로그에만 남긴다 */
public class SolarException extends RuntimeException {
    public SolarException(String message) {
        super(message);
    }

    public SolarException(String message, Throwable cause) {
        super(message, cause);
    }
}
