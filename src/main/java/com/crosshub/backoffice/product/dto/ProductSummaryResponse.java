package com.crosshub.backoffice.product.dto;

import com.crosshub.backoffice.product.Product;
import java.time.OffsetDateTime;

/**
 * 목록용 요약 응답. 무거운 JSON 컬럼(priceTable/blocks/schedule)은 제외.
 * partnerName은 Product.partner(읽기전용 조인)에서 가져온다 — from()은 세션이 열린
 * (즉 @Transactional 메서드) 안에서 호출해야 한다. 안 그러면 LazyInitializationException.
 * translation = 번역 상태(none|auto|reviewed, 임시저장은 null), pendingChange = 가장 최근 변경요청(대기중·반려됨) 또는 null,
 * nameLocalized/nameAi = locale 을 주면 그 언어의 번역 상품명(없으면 원문)과 번역문인지 여부.
 */
public record ProductSummaryResponse(
        String id,
        String partnerId,
        String partnerName,
        String status,
        String category,
        String confirmType,
        String name,
        String nameLocalized,
        boolean nameAi,
        String thumbnail,
        Integer priceFrom,
        String reviewRejectReason,
        String translation,
        com.crosshub.backoffice.product.change.ChangeRequestDtos.ChangeRequestRow pendingChange,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProductSummaryResponse from(Product p) {
        return from(p, null, p.getName(), null);
    }

    public static ProductSummaryResponse from(Product p, String translation, String nameLocalized,
                                              com.crosshub.backoffice.product.change.ChangeRequestDtos.ChangeRequestRow pendingChange) {
        String thumb = (p.getImages() != null && p.getImages().length > 0) ? p.getImages()[0] : null;
        String localized = (nameLocalized == null || nameLocalized.isBlank()) ? p.getName() : nameLocalized;
        return new ProductSummaryResponse(
                p.getId(), p.getPartnerId(), p.getPartner() != null ? p.getPartner().getName() : null,
                p.getStatus(), p.getCategory(), p.getConfirmType(),
                p.getName(), localized, !localized.equals(p.getName()), thumb, p.getPriceFrom(), p.getReviewRejectReason(),
                translation, pendingChange,
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
