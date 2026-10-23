package com.crosshub.backoffice.booking.dto;

/** 업체가 견적을 보낼 때 (POST /api/v1/bookings/{id}/quote). */
public record BookingQuoteRequest(int amount, String message) {
}
