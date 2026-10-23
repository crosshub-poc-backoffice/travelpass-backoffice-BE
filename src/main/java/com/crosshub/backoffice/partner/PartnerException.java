package com.crosshub.backoffice.partner;

import org.springframework.http.HttpStatus;

/** 업체 쓰기 실패. code는 FE 에러 키(messages/*.json errors)와 같은 이름 (AccountException·ProductException과 같은 패턴) */
public class PartnerException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public PartnerException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
