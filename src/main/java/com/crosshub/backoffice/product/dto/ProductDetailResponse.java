package com.crosshub.backoffice.product.dto;

import com.crosshub.backoffice.product.Product;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 상세 응답. jsonb 컬럼은 @JsonRawValue로 "다시 문자열화하지 않고" 원본 JSON 그대로 내보낸다
 * (FE가 Prisma 때와 같은 모양으로 받도록).
 */
public record ProductDetailResponse(
        String id,
        String partnerId,
        String partnerName,
        String status,
        boolean hiddenByPartnerOff,
        String reviewRejectReason,
        String confirmType,
        String category,
        String name,
        List<String> images,
        String description,
        String sourceLocale,
        @JsonRawValue String schedule,
        LocalDate saleStart,
        LocalDate saleEnd,
        int deadlineDays,
        String deadlineTime,
        @JsonRawValue String cancelRules,
        Integer priceFrom,
        @JsonRawValue String priceTable,
        @JsonRawValue String blocks,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProductDetailResponse from(Product p) {
        return new ProductDetailResponse(
                p.getId(), p.getPartnerId(),
                p.getPartner() != null ? p.getPartner().getName() : null,
                p.getStatus(), p.isHiddenByPartnerOff(), p.getReviewRejectReason(),
                p.getConfirmType(), p.getCategory(), p.getName(),
                p.getImages() == null ? List.of() : Arrays.asList(p.getImages()),
                p.getDescription(), p.getSourceLocale(),
                nn(p.getSchedule(), "{}"), p.getSaleStart(), p.getSaleEnd(),
                p.getDeadlineDays(), p.getDeadlineTime(),
                nn(p.getCancelRules(), "[]"), p.getPriceFrom(),
                nn(p.getPriceTable(), "null"), nn(p.getBlocks(), "[]"),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    private static String nn(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
