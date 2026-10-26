package com.crosshub.backoffice.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Map;

/**
 * 목록에 보여줄 대표가: 첫 번째 기준 기준 가장 싼 단가. 숙박은 요금표가 아니라 객실 기본 요금(roomPricing.base)에서 찾는다.
 * FE lib/products/types.ts 의 priceFrom() 포트 — 변경요청 승인 반영·검수에서 BE 가 직접 계산한다.
 */
public final class PriceFrom {

    private PriceFrom() {}

    public static Integer of(String priceTableJson, ObjectMapper om) {
        if (priceTableJson == null || priceTableJson.isBlank()) return null;
        JsonNode pt;
        try {
            pt = om.readTree(priceTableJson);
        } catch (Exception e) {
            return null;
        }
        return of(pt);
    }

    public static Integer of(JsonNode pt) {
        if (pt == null || !pt.isObject()) return null;
        Integer min = null;
        if (pt.path("roomTypes").size() > 0) {
            for (Iterator<Map.Entry<String, JsonNode>> it = pt.path("roomPricing").path("base").fields(); it.hasNext(); ) {
                JsonNode price = it.next().getValue().path("price");
                if (price.isNumber()) min = min == null ? price.asInt() : Math.min(min, price.asInt());
            }
            return min;
        }
        JsonNode base = pt.path("categories").path(0);
        if (!base.isObject()) return null;
        String baseId = base.path("id").asText();
        for (JsonNode plan : pt.path("plans")) {
            JsonNode price = plan.path("prices").path(baseId);
            if (price.isNumber()) min = min == null ? price.asInt() : Math.min(min, price.asInt());
        }
        return min;
    }
}
