package com.crosshub.backoffice.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

/**
 * 상품 생성/수정 요청 바디.
 * - jsonb 컬럼(schedule/cancelRules/priceTable/blocks)은 JsonNode로 받아 그대로 문자열화해 저장한다
 *   (FE가 Prisma 때와 같은 객체/배열을 그대로 보내도록).
 * - 수정(PUT)에서는 null인 필드는 "그대로 둠"으로 본다.
 * - partnerId: crosshub(운영자)가 생성할 때만 의미. partner가 보내면 무시하고 본인 업체로 강제한다.
 */
public record ProductUpsertRequest(
        String partnerId,
        String name,
        String category,
        String confirmType,
        String description,
        String sourceLocale,
        List<String> images,
        JsonNode schedule,
        JsonNode cancelRules,
        JsonNode priceTable,
        JsonNode blocks,
        LocalDate saleStart,
        LocalDate saleEnd,
        Integer deadlineDays,
        String deadlineTime,
        Integer priceFrom) {
}
