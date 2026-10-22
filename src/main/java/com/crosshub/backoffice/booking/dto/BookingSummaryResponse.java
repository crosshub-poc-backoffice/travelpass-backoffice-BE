package com.crosshub.backoffice.booking.dto;

import com.crosshub.backoffice.booking.Booking;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 목록용 요약. status/confirmType은 DB enum 원값(pending/confirmed/instant…) 그대로 — 라벨 변환은 FE에서.
 * FE 목록 화면(BookingRow)이 금액 스냅샷·견적·취소 정보까지 한 줄에 다 쓰기 때문에 BookingDetailResponse와
 * 거의 같은 필드를 들고 있다 — 전부 Booking 엔티티의 스칼라 컬럼이라 새 조인은 없다.
 * productCancelRules는 Detail에도 없는 값으로, FE가 "지금 취소하면 얼마"(cancelFeePreview)를 계산하는 데 쓴다.
 */
public record BookingSummaryResponse(
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
        @JsonRawValue String productCancelRules) {

    public static BookingSummaryResponse from(Booking b) {
        String pb = (b.getPriceBreakdown() == null || b.getPriceBreakdown().isBlank()) ? "null" : b.getPriceBreakdown();
        String cancelRules = (b.getProduct() == null || b.getProduct().getCancelRules() == null || b.getProduct().getCancelRules().isBlank())
                ? "[]" : b.getProduct().getCancelRules();
        return new BookingSummaryResponse(
                b.getId(), b.getProductId(),
                b.getProduct() != null ? b.getProduct().getName() : null,
                b.getPartnerId(),
                b.getPartner() != null ? b.getPartner().getName() : null,
                b.getPartner() != null ? b.getPartner().getCurrency() : null,
                b.getCustomerName(), b.getPhone(), b.getEmail(), b.getNationality(), b.getUseDate(), b.getPeople(),
                b.getAmount(), b.getCommissionRate(), b.getCommission(), b.getNet(), b.getCancelFee(), b.getCancelReason(),
                pb, b.getStatus(), b.getConfirmType(), b.getRequestedAt(),
                b.getQuoteAmount(), b.getQuoteValidUntil(), b.getQuoteMessage(), b.getCustomerDecision(),
                cancelRules);
    }
}
