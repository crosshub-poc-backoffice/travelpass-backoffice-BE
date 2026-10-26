package com.crosshub.backoffice.product.inspect;

import com.crosshub.backoffice.booking.SaleRange;
import com.crosshub.backoffice.product.PriceFrom;
import com.crosshub.backoffice.product.inspect.InspectTarget.CancelRule;
import com.crosshub.backoffice.product.inspect.InspectTarget.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 규칙 검사기 모음. 전부 산수라 Solar 를 쓰지 않는다. FE lib/server/inspect/rules/* 포트.
 *   price:    PRICE_ZERO · CHILD_PRICE_OVER_ADULT · PRICE_OUTLIER
 *   cancel:   CANCEL_RULE_RANGE · CANCEL_RULE_DUPLICATE · CANCEL_RULE_NOT_INCREASING
 *   schedule: DEADLINE_INVALID · SALE_RANGE_EMPTY
 *   diff(변경요청 전용): PRICE_CHANGED_LARGE · CANCEL_RULE_RELAXED · IMAGES_ALL_REMOVED · PARTNER_CHANGED
 */
public final class RuleCheckers {

    private RuleCheckers() {}

    private static final Pattern ADULT = Pattern.compile("성인|대인|어른|adult", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHILD = Pattern.compile("아동|소인|어린이|child|kid", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    /** 0을 하나 더 붙이면 10배가 된다. 상품 안의 다른 단가들과 견줘서 그 정도로 튀는 칸을 찾는다 */
    static final int OUTLIER_RATIO = 10;
    /** 대표가가 이만큼 움직이면 심사자가 한 번 보는 게 맞다 */
    static final double PRICE_JUMP = 0.3;

    public static List<Checker> all(ObjectMapper om) {
        List<Checker> out = new ArrayList<>();
        out.add(rule("PRICE_ZERO", RuleCheckers::priceZero));
        out.add(rule("CHILD_PRICE_OVER_ADULT", RuleCheckers::childOverAdult));
        out.add(rule("PRICE_OUTLIER", RuleCheckers::priceOutlier, t -> allPrices(t.proposed().priceTable()).size() >= 3));
        out.add(rule("CANCEL_RULE_RANGE", RuleCheckers::cancelRange));
        out.add(rule("CANCEL_RULE_DUPLICATE", RuleCheckers::cancelDuplicate));
        out.add(rule("CANCEL_RULE_NOT_INCREASING", RuleCheckers::cancelNotIncreasing, t -> t.proposed().cancelRules().size() >= 2));
        out.add(rule("DEADLINE_INVALID", RuleCheckers::deadlineInvalid));
        out.add(rule("SALE_RANGE_EMPTY", t -> saleRangeEmpty(t, om)));
        out.add(rule("PRICE_CHANGED_LARGE", RuleCheckers::priceJump, InspectTarget::isEdit));
        out.add(rule("CANCEL_RULE_RELAXED", RuleCheckers::cancelRelaxed, InspectTarget::isEdit));
        out.add(rule("IMAGES_ALL_REMOVED", RuleCheckers::imagesRemoved, InspectTarget::isEdit));
        out.add(rule("PARTNER_CHANGED", RuleCheckers::partnerChanged, InspectTarget::isEdit));
        return out;
    }

    static Checker rule(String code, Function<InspectTarget, List<Finding>> run) {
        return rule(code, run, t -> true);
    }

    static Checker rule(String code, Function<InspectTarget, List<Finding>> run, Predicate<InspectTarget> applies) {
        return new Checker() {
            @Override public String code() { return code; }
            @Override public boolean isAi() { return false; }
            @Override public boolean appliesTo(InspectTarget t) { return applies.test(t); }
            @Override public List<Finding> run(InspectTarget t) { return run.apply(t); }
        };
    }

    // ── 요금 ──────────────────────────────────────────────

    /** 0엔 칸. 무료 상품일 수도 있으니 경고만 */
    static List<Finding> priceZero(InspectTarget t) {
        JsonNode pt = t.proposed().priceTable();
        List<Finding> out = new ArrayList<>();
        for (JsonNode plan : pt.path("plans")) {
            for (Iterator<Map.Entry<String, JsonNode>> it = plan.path("prices").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isNumber() && e.getValue().asDouble() == 0) {
                    out.add(Finding.rule("PRICE_ZERO", "medium", "priceTable.plans." + text(plan, "id") + ".prices." + e.getKey(),
                            evidence("plan", text(plan, "name"), "category", categoryName(pt, e.getKey()))));
                }
            }
        }
        return out;
    }

    /** 아동 요금이 성인 요금보다 비싼 칸. 기준 이름으로 성인·아동을 찾는다 */
    static List<Finding> childOverAdult(InspectTarget t) {
        JsonNode pt = t.proposed().priceTable();
        JsonNode adult = null;
        JsonNode child = null;
        for (JsonNode c : pt.path("categories")) {
            if (adult == null && ADULT.matcher(text(c, "name")).find()) adult = c;
            if (child == null && CHILD.matcher(text(c, "name")).find()) child = c;
        }
        if (adult == null || child == null) return List.of();
        List<Finding> out = new ArrayList<>();
        for (JsonNode plan : pt.path("plans")) {
            JsonNode a = plan.path("prices").path(text(adult, "id"));
            JsonNode c = plan.path("prices").path(text(child, "id"));
            if (a.isNumber() && c.isNumber() && c.asDouble() > a.asDouble()) {
                out.add(Finding.rule("CHILD_PRICE_OVER_ADULT", "high", "priceTable.plans." + text(plan, "id") + ".prices." + text(child, "id"),
                        evidence("plan", text(plan, "name"), "adultName", text(adult, "name"), "adult", a.numberValue(),
                                "childName", text(child, "name"), "child", c.numberValue())));
            }
        }
        return out;
    }

    /** 요금표에 실제로 적힌 단가 전부 (요금표 + 숙박 객실 기본요금) */
    static List<Double> allPrices(JsonNode pt) {
        List<Double> out = new ArrayList<>();
        for (JsonNode plan : pt.path("plans")) {
            for (JsonNode v : plan.path("prices")) if (v.isNumber()) out.add(v.asDouble());
        }
        for (JsonNode r : pt.path("roomPricing").path("base")) {
            if (r.path("price").isNumber()) out.add(r.path("price").asDouble());
        }
        return out;
    }

    static Double median(List<Double> xs) {
        if (xs.isEmpty()) return null;
        List<Double> s = new ArrayList<>(xs);
        s.sort(Comparator.naturalOrder());
        int mid = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(mid) : (s.get(mid - 1) + s.get(mid)) / 2;
    }

    /** 같은 상품 안에서 자릿수가 튀는 단가 (0을 더 붙였거나 빠뜨린 오타) */
    static List<Finding> priceOutlier(InspectTarget t) {
        JsonNode pt = t.proposed().priceTable();
        List<Double> prices = allPrices(pt).stream().filter(p -> p > 0).toList();
        Double mid = median(prices);
        if (mid == null || prices.size() < 3) return List.of();
        List<Finding> out = new ArrayList<>();
        for (JsonNode plan : pt.path("plans")) {
            for (Iterator<Map.Entry<String, JsonNode>> it = plan.path("prices").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isNumber()) {
                    outlier(out, e.getValue().asDouble(), mid, "priceTable.plans." + text(plan, "id") + ".prices." + e.getKey(),
                            evidence("plan", text(plan, "name"), "category", categoryName(pt, e.getKey())));
                }
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = pt.path("roomPricing").path("base").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode price = e.getValue().path("price");
            if (price.isNumber()) {
                String room = e.getKey();
                for (JsonNode r : pt.path("roomTypes")) if (text(r, "id").equals(e.getKey())) room = text(r, "name");
                outlier(out, price.asDouble(), mid, "priceTable.roomPricing.base." + e.getKey(), evidence("room", room));
            }
        }
        return out;
    }

    private static void outlier(List<Finding> out, double price, double mid, String field, Map<String, Object> where) {
        if (price <= 0) return;
        double ratio = price / mid;
        if (ratio >= OUTLIER_RATIO || ratio <= 1.0 / OUTLIER_RATIO) {
            Map<String, Object> ev = new LinkedHashMap<>(where);
            ev.put("price", num(price));
            ev.put("median", num(mid));
            ev.put("ratio", Math.round(ratio * 100) / 100.0);
            out.add(Finding.rule("PRICE_OUTLIER", "high", field, ev));
        }
    }

    // ── 취소 규정 ──────────────────────────────────────────

    /** percent 가 0~100 밖, daysBefore 가 음수 */
    static List<Finding> cancelRange(InspectTarget t) {
        List<Finding> out = new ArrayList<>();
        List<CancelRule> rules = t.proposed().cancelRules();
        for (int i = 0; i < rules.size(); i++) {
            CancelRule r = rules.get(i);
            if (r.percent() < 0 || r.percent() > 100 || r.daysBefore() < 0) {
                out.add(Finding.rule("CANCEL_RULE_RANGE", "high", "cancelRules." + i,
                        evidence("index", i, "daysBefore", r.daysBefore(), "percent", r.percent())));
            }
        }
        return out;
    }

    /** 같은 daysBefore 가 두 번 — 어느 줄이 이기는지 사람이 알 수 없다 */
    static List<Finding> cancelDuplicate(InspectTarget t) {
        Map<Integer, Integer> seen = new HashMap<>();
        List<Finding> out = new ArrayList<>();
        List<CancelRule> rules = t.proposed().cancelRules();
        for (int i = 0; i < rules.size(); i++) {
            CancelRule r = rules.get(i);
            Integer first = seen.get(r.daysBefore());
            if (first != null) {
                out.add(Finding.rule("CANCEL_RULE_DUPLICATE", "medium", "cancelRules." + i,
                        evidence("daysBefore", r.daysBefore(), "rows", List.of(first, i))));
            } else {
                seen.put(r.daysBefore(), i);
            }
        }
        return out;
    }

    /** 출발일에 가까워질수록 수수료가 싸지는 줄. 거의 항상 입력 실수다 */
    static List<Finding> cancelNotIncreasing(InspectTarget t) {
        List<CancelRule> sorted = new ArrayList<>(t.proposed().cancelRules());
        sorted.sort((a, b) -> Integer.compare(b.daysBefore(), a.daysBefore()));
        List<Finding> out = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            CancelRule prev = sorted.get(i - 1);
            CancelRule cur = sorted.get(i);
            if (cur.percent() < prev.percent()) {
                out.add(Finding.rule("CANCEL_RULE_NOT_INCREASING", "medium", "cancelRules",
                        evidence("earlier", ruleMap(prev), "later", ruleMap(cur))));
            }
        }
        return out;
    }

    // ── 판매 일정·예약 마감 ─────────────────────────────────

    /** 예약 마감 입력이 형태에 안 맞음 */
    static List<Finding> deadlineInvalid(InspectTarget t) {
        Snapshot p = t.proposed();
        List<Finding> out = new ArrayList<>();
        if (p.deadlineDays() < 0 || p.deadlineDays() > 365) {
            out.add(Finding.rule("DEADLINE_INVALID", "high", "deadlineDays", evidence("deadlineDays", p.deadlineDays())));
        }
        if (!TIME.matcher(p.deadlineTime()).matches()) {
            out.add(Finding.rule("DEADLINE_INVALID", "high", "deadlineTime", evidence("deadlineTime", p.deadlineTime())));
        }
        return out;
    }

    /**
     * 판매 일정에서 실제로 파는 날이 하나도 안 나온다 — 노출해도 아무도 예약할 수 없다.
     * 등급이 low 인 이유: saleStart/saleEnd 는 지금 앱 어디서도 읽지 않아서, 판매기간이 비어 있는 게 정말 비정상인지
     * 확인하지 못했다. 실제 상품 데이터로 노이즈를 재본 뒤 등급을 정한다 (FE HANDOVER 참고)
     */
    static List<Finding> saleRangeEmpty(InspectTarget t, ObjectMapper om) {
        try {
            if (SaleRange.of(t.proposed().scheduleJson(), om) != null) return List.of();
        } catch (RuntimeException e) {
            // 아래에서 지적으로 남긴다
        }
        return List.of(Finding.rule("SALE_RANGE_EMPTY", "low", "schedule", null));
    }

    // ── 변경요청 전용 (바뀐 폭이 큰 곳) ─────────────────────

    /** 대표가가 30% 넘게 오르내림 */
    static List<Finding> priceJump(InspectTarget t) {
        Integer before = PriceFrom.of(t.current().priceTable());
        Integer after = PriceFrom.of(t.proposed().priceTable());
        if (before == null || after == null || before == 0) return List.of();
        double rate = (after - before) / (double) before;
        if (Math.abs(rate) < PRICE_JUMP) return List.of();
        return List.of(Finding.rule("PRICE_CHANGED_LARGE", "high", "priceTable",
                evidence("before", before, "after", after, "rate", Math.round(rate * 1000) / 10.0)));
    }

    /** 취소 수수료가 느슨해짐 (같은 시점의 수수료율이 낮아지거나 줄이 사라짐) */
    static List<Finding> cancelRelaxed(InspectTarget t) {
        List<Finding> out = new ArrayList<>();
        for (CancelRule b : t.current().cancelRules()) {
            CancelRule a = t.proposed().cancelRules().stream().filter(r -> r.daysBefore() == b.daysBefore()).findFirst().orElse(null);
            if (a == null) {
                out.add(Finding.rule("CANCEL_RULE_RELAXED", "medium", "cancelRules", evidence("removed", ruleMap(b))));
            } else if (a.percent() < b.percent()) {
                out.add(Finding.rule("CANCEL_RULE_RELAXED", "medium", "cancelRules",
                        evidence("daysBefore", b.daysBefore(), "before", b.percent(), "after", a.percent())));
            }
        }
        return out;
    }

    /** 사진이 있던 상품에서 사진이 전부 사라짐 */
    static List<Finding> imagesRemoved(InspectTarget t) {
        if (t.current().images().isEmpty() || !t.proposed().images().isEmpty()) return List.of();
        return List.of(Finding.rule("IMAGES_ALL_REMOVED", "medium", "images", evidence("before", t.current().images().size())));
    }

    /** 업체가 바뀜. 수정은 업체를 안 바꾸므로(지난 예약·정산이 넘어간다) 들어오면 안 되는 값이다 */
    static List<Finding> partnerChanged(InspectTarget t) {
        String before = t.current().partnerId();
        String after = t.proposed().partnerId();
        if (before == null ? after == null : before.equals(after)) return List.of();
        return List.of(Finding.rule("PARTNER_CHANGED", "high", "partnerId", evidence("before", before, "after", after)));
    }

    // ── 도우미 ─────────────────────────────────────────────

    private static Map<String, Object> ruleMap(CancelRule r) {
        return evidence("daysBefore", r.daysBefore(), "percent", r.percent());
    }

    private static String categoryName(JsonNode pt, String catId) {
        for (JsonNode c : pt.path("categories")) if (text(c, "id").equals(catId)) return text(c, "name");
        return catId;
    }

    private static Object num(double v) {
        return v == Math.rint(v) ? (Object) (long) v : (Object) v;
    }

    static Map<String, Object> evidence(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? "" : v.asText();
    }
}
