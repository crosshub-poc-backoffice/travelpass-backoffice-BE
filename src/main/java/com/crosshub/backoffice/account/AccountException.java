package com.crosshub.backoffice.account;

import org.springframework.http.HttpStatus;

/** 계정 작업 실패. code는 FE 에러 키(messages/*.json errors)와 같은 이름으로 돌려준다 */
public class AccountException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AccountException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
