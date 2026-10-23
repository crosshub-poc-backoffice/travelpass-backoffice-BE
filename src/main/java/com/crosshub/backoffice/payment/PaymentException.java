package com.crosshub.backoffice.payment;

import org.springframework.http.HttpStatus;

/** 결제 설정 쓰기 실패. code는 FE 에러 키와 같은 이름 (currency·required·tooLong·feeRate·payDay·shareRate·sharesExceedCommission ...) */
public class PaymentException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public PaymentException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
