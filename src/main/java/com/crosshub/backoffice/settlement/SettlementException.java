package com.crosshub.backoffice.settlement;

import org.springframework.http.HttpStatus;

/** 정산 쓰기 실패. code는 FE 에러 키와 같은 이름 (monthNotEnded·unfinishedBookings·alreadyPaid·notFound·forbidden) */
public class SettlementException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public SettlementException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
