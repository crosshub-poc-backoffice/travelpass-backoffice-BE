package com.crosshub.backoffice.booking.dto;

/** 견적 거절, 이용완료 처리, 예약 취소 (POST /api/v1/bookings/{id}/status). reason은 거절·취소일 때만 쓴다(선택). */
public record BookingStatusRequest(String status, String reason) {
}
