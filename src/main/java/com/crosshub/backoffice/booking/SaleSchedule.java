package com.crosshub.backoffice.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 상품 판매 일정(Product.schedule JSON)에서 "그 날 파는가 / 정원은 몇 명인가"를 구한다.
 * FE lib/products/schedule.ts 의 resolve() 를 그대로 포트 — 규칙이 한 글자라도 다르면 앱과 백오피스가 다른 답을 내므로
 * 로직을 새로 쓰지 않고 1:1 로 옮겼다.
 *
 *   판매 일정 = 기본 일정 1개 + 예외 여러 개. 예외는 기본 일정보다 우선하고, 예외끼리 겹치면 나중 것이 이긴다.
 *   times = 출발 시각(회차) 목록, [] 이면 하루 단위. 회차별 값이 있으면 그걸, 없으면 예외 전체 값을, 예외가 없으면 기본 일정.
 *   open = 회차 중 하나라도 파는지. maxPeople 은 첫 열린 회차(없으면 첫 회차)의 값. null = 무제한.
 */
public final class SaleSchedule {

    private SaleSchedule() {}

    /** 그 날의 판매 상태. maxPeople null = 정원 제한 없음 */
    public record Day(boolean open, Integer maxPeople) {}

    /**
     * schedule JSON 이 비었거나 상품 등록 화면에서 한 번도 설정 안 된 "{}"(base 없음)이면 null — 정원 제한 없음으로 본다.
     * (Booking 에 회차를 저장하지 않아 하루 단위로만 본다. FE remainingCapacityForDates 와 같은 한계)
     */
    public static Day resolve(String scheduleJson, LocalDate date, ObjectMapper om) {
        if (scheduleJson == null || scheduleJson.isBlank()) return null;
        JsonNode s;
        try {
            s = om.readTree(scheduleJson);
        } catch (Exception e) {
            return null;
        }
        if (s == null || !s.has("base")) return null;
        String d = date.toString(); // YYYY-MM-DD, 문자열 비교로 날짜 순서를 본다 (FE 와 동일)
        JsonNode base = s.get("base");

        JsonNode ex = null;
        JsonNode exceptions = s.get("exceptions");
        if (exceptions != null && exceptions.isArray()) {
            for (JsonNode e : exceptions) { // 나중 것이 이긴다 → 끝까지 돌며 덮어쓴다 (findLast)
                if (text(e, "start").compareTo(d) <= 0 && d.compareTo(text(e, "end")) <= 0) ex = e;
            }
        }
        boolean uniform = ex != null && (ex.get("slots") == null || ex.get("slots").isEmpty());

        List<String> times = new ArrayList<>();
        if (base.get("times") != null) for (JsonNode t : base.get("times")) times.add(t.asText());
        if (times.isEmpty()) times.add("");

        record Slot(boolean open, JsonNode people) {}
        List<Slot> slots = new ArrayList<>();
        for (String time : times) {
            JsonNode own = ex != null && ex.get("slots") != null ? ex.get("slots").get(time) : null;
            if (own != null) {
                slots.add(new Slot(own.path("open").asBoolean(false), own));
            } else if (uniform) {
                slots.add(new Slot(ex.path("open").asBoolean(false), ex));
            } else {
                JsonNode sp = base.get("slotPeople") != null ? base.get("slotPeople").get(time) : null;
                slots.add(new Slot(baseCovers(base, date, d), sp != null ? sp : base));
            }
        }
        Slot first = slots.stream().filter(Slot::open).findFirst().orElse(slots.get(0));
        JsonNode max = first.people().get("maxPeople");
        return new Day(slots.stream().anyMatch(Slot::open), (max == null || max.isNull()) ? null : max.asInt());
    }

    /** 기본 일정이 그 날을 덮는가: end 가 있고 start ≤ d ≤ end 이며 요일이 맞을 때. 요일은 일요일=0 (JS getUTCDay 와 동일) */
    private static boolean baseCovers(JsonNode base, LocalDate date, String d) {
        String end = text(base, "end");
        if (end.isEmpty()) return false;
        if (!(text(base, "start").compareTo(d) <= 0 && d.compareTo(end) <= 0)) return false;
        int weekday = date.getDayOfWeek().getValue() % 7;
        JsonNode weekdays = base.get("weekdays");
        if (weekdays == null || !weekdays.isArray()) return false;
        for (JsonNode w : weekdays) if (w.asInt() == weekday) return true;
        return false;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
