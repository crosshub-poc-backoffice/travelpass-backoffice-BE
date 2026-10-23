package com.crosshub.backoffice.booking.dto;

/** 환불 완료 처리 (POST /api/v1/bookings/{id}/refund). cancelFee는 취소표 수수료보다 낮춰서(깎아주기만) 조정 가능. */
public record BookingRefundRequest(int cancelFee) {
}
