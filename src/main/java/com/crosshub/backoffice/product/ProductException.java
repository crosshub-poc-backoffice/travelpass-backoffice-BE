package com.crosshub.backoffice.product;

import org.springframework.http.HttpStatus;

/** 상품 쓰기 작업 실패. code는 FE 에러 키(messages/*.json errors)와 같은 이름으로 돌려준다 (account.AccountException과 동일 패턴) */
public class ProductException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ProductException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
