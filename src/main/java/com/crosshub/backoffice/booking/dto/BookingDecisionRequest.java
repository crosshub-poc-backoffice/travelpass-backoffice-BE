package com.crosshub.backoffice.booking.dto;

/** 고객이 견적에 응답할 때(관리자 화면에서 대신) (POST /api/v1/bookings/{id}/customer-decision). decision: accepted | declined */
public record BookingDecisionRequest(String decision) {
}
