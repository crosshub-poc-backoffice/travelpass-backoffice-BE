package com.crosshub.backoffice.inquiry.dto;

/**
 * 관리자 답변 등록 (POST /api/v1/inquiries/{id}/answer).
 * text·textTranslated는 화면에서 번역 검수까지 끝낸 값을 그대로 받는다.
 * locale은 답변을 쓴 관리자의 화면 언어(= text의 언어).
 */
public record PostAnswerRequest(String text, String textTranslated, String locale) {
}
