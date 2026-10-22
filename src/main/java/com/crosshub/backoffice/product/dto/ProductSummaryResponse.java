package com.crosshub.backoffice.product.dto;

import com.crosshub.backoffice.product.Product;
import java.time.OffsetDateTime;

/**
 * 목록용 요약 응답. 무거운 JSON 컬럼(priceTable/blocks/schedule)은 제외.
 * partnerName은 Product.partner(읽기전용 조인)에서 가져온다 — from()은 세션이 열린
 * (즉 @Transactional 메서드) 안에서 호출해야 한다. 안 그러면 LazyInitializationException.
 * 번역 상태(translation)는 별도 서브시스템이라 아직 포함하지 않는다.
 */
public record ProductSummaryResponse(
        String id,
        String partnerId,
        String partnerName,
        String status,
        String category,
        String confirmType,
        String name,
        String thumbnail,
        Integer priceFrom,
        String reviewRejectReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProductSummaryResponse from(Product p) {
        String thumb = (p.getImages() != null && p.getImages().length > 0) ? p.getImages()[0] : null;
        return new ProductSummaryResponse(
                p.getId(), p.getPartnerId(), p.getPartner() != null ? p.getPartner().getName() : null,
                p.getStatus(), p.getCategory(), p.getConfirmType(),
                p.getName(), thumb, p.getPriceFrom(), p.getReviewRejectReason(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
