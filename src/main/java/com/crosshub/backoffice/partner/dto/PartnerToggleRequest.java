package com.crosshub.backoffice.partner.dto;

/** 활성화·비활성화 (POST /api/v1/partners/{id}/toggle-active). restoreProducts: 다시 켤 때 비활성화로 숨긴 상품을 노출중으로 되돌릴지 */
public record PartnerToggleRequest(boolean restoreProducts) {
}
