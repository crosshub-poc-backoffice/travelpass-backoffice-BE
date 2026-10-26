package com.crosshub.backoffice.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 판매 기간 = 실제로 파는 첫날 ~ 마지막 날. FE lib/products/schedule.ts 의 saleRange() 포트.
 * 상품 저장 때 FE 가 계산해 보내던 saleStart/saleEnd 를 BE 가 변경요청 승인 반영 때 직접 계산하려고 옮겼다.
 * 파는 날이 없거나 일정이 비었으면 null.
 */
public final class SaleRange {

    private SaleRange() {}

    public record Range(LocalDate start, LocalDate end) {}

    public static Range of(String scheduleJson, ObjectMapper om) {
        if (scheduleJson == null || scheduleJson.isBlank()) return null;
        JsonNode s;
        try {
            s = om.readTree(scheduleJson);
        } catch (Exception e) {
            return null;
        }
        if (s == null || !s.has("base")) return null;
        JsonNode base = s.get("base");
        String baseEnd = text(base, "end");
        List<String> starts = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        if (!baseEnd.isEmpty()) {
            if (!text(base, "start").isEmpty()) starts.add(text(base, "start"));
            ends.add(baseEnd);
        }
        for (JsonNode e : s.path("exceptions")) {
            if (!e.path("open").asBoolean(false)) continue;
            if (!text(e, "start").isEmpty()) starts.add(text(e, "start"));
            if (!text(e, "end").isEmpty()) ends.add(text(e, "end"));
        }
        if (starts.isEmpty() || ends.isEmpty()) return null;
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(starts.stream().min(String::compareTo).orElseThrow());
            to = LocalDate.parse(ends.stream().max(String::compareTo).orElseThrow());
        } catch (Exception e) {
            return null;
        }
        LocalDate first = null;
        LocalDate last = null;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            SaleSchedule.Day day = SaleSchedule.resolve(scheduleJson, d, om);
            if (day != null && day.open()) {
                if (first == null) first = d;
                last = d;
            }
        }
        return first == null ? null : new Range(first, last);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
