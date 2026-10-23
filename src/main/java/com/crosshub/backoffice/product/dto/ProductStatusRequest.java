package com.crosshub.backoffice.product.dto;

/** 상태 전환 요청. reason은 review→hidden(반려)일 때만 필수. */
public record ProductStatusRequest(String status, String reason) {
}
