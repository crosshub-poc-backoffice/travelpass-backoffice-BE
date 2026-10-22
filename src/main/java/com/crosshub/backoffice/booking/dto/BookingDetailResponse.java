package com.crosshub.backoffice.booking.dto;

import com.crosshub.backoffice.booking.Booking;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 상세. 금액 스냅샷·견적·취소 정보 포함. priceBreakdown은 원본 JSON 그대로. */
public record BookingDetailResponse(
        String id,
        String productId,
        String productName,
        String partnerId,
        String partnerName,
        String currency,
        String customerName,
        String phone,
        String email,
        String nationality,
        LocalDate useDate,
        int people,
        int amount,
        int commissionRate,
        int commission,
        int net,
        Integer cancelFee,
        String cancelReason,
        @JsonRawValue String priceBreakdown,
        String status,
        String confirmType,
        OffsetDateTime requestedAt,
        Integer quoteAmount,
        LocalDate quoteValidUntil,
        String quoteMessage,
        String customerDecision,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static BookingDetailResponse from(Booking b) {
        String pb = (b.getPriceBreakdown() == null || b.getPriceBreakdown().isBlank()) ? "null" : b.getPriceBreakdown();
        return new BookingDetailResponse(
                b.getId(), b.getProductId(),
                b.getProduct() != null ? b.getProduct().getName() : null,
                b.getPartnerId(),
                b.getPartner() != null ? b.getPartner().getName() : null,
                b.getPartner() != null ? b.getPartner().getCurrency() : null,
                b.getCustomerName(), b.getPhone(), b.getEmail(), b.getNationality(),
                b.getUseDate(), b.getPeople(), b.getAmount(),
                b.getCommissionRate(), b.getCommission(), b.getNet(), b.getCancelFee(), b.getCancelReason(),
                pb, b.getStatus(), b.getConfirmType(), b.getRequestedAt(),
                b.getQuoteAmount(), b.getQuoteValidUntil(), b.getQuoteMessage(), b.getCustomerDecision(),
                b.getCreatedAt(), b.getUpdatedAt());
    }
}
