package com.crosshub.backoffice.payment.dto;

import java.time.OffsetDateTime;

/** 결제 설정·결제내역 요청/응답. FE lib/server/payments/service.ts 타입과 같은 필드 */
public final class PaymentDtos {
    private PaymentDtos() {}

    public record PgSettingRequest(String pgName, Double feeRate, Integer payDay) {}

    public record AffiliateShareRequest(Integer shareRate, String bankName, String bankAccountNumber, String bankAccountHolder) {}

    public record BankRequest(String bankName, String bankAccountNumber, String bankAccountHolder) {}

    public record MyAffiliateResponse(String name, Integer shareRate, String bankName, String bankAccountNumber, String bankAccountHolder) {}

    public record PaymentRow(String id, OffsetDateTime createdAt, String bookingId, String partner, String currency,
                             String productId, String productName, String type, int amount, String pg, String pgTransactionId) {}
}
