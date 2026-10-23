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
        return from(p, p.getPartner() != null ? p.getPartner().getName() : null);
    }

    /**
     * partnerName을 직접 받는 버전. 방금 save()한 새 엔티티는 Hibernate 1차 캐시(영속성 컨텍스트) 때문에
     * 같은 트랜잭션 안에서 findById로 "재조회"해도 실제로 DB를 다시 안 읽고 같은 인스턴스를 돌려줘서
     * partner(지연 조인) 프록시가 그대로 비어 있다 — ProductService.create()는 이미 가진 Partner로 직접 채운다.
     */
    public static ProductDetailResponse from(Product p, String partnerName) {
        return new ProductDetailResponse(
                p.getId(), p.getPartnerId(),
                partnerName,
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
