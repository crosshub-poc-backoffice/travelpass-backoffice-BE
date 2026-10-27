package com.crosshub.backoffice.product.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품에서 번역할 글을 키 → 원문으로 뽑는다. FE lib/products/translation.ts 의 productTexts()/sourceMap() 포트.
 * 키는 블록·플랜 등의 id 로 만들어서, 순서를 바꾸거나 줄을 추가해도 기존 번역이 제자리를 찾는다. 빈 칸은 뺀다.
 * (FE 가 저장 전에 validate.ts 로 정리한 JSON 을 그대로 읽는다 — 빈 줄은 이미 걸러져 있다)
 *
 *   name, description
 *   pt.title, pt.cat.<id>, pt.plan.<id>, pt.room.<id>, pt.text
 *   b.<id>.title / .text / .g.<gid>.title / .g.<gid>.col.<cid> / .g.<gid>.opt.<oid> / .ccol.<cid> / .ch.<chid> / .ch.<chid>.v.<colId>
 */
public final class ProductTexts {

    private ProductTexts() {}

    /** 번역 화면의 행 하나. section: "basic" | "price" | 블록 순서(0부터, 숫자 문자열), field: 행 이름 키 (FE TextField) */
    public record Entry(String key, Object section, String field, String source) {}

    public static Map<String, String> sourceMap(String name, String description, String priceTableJson, String blocksJson, ObjectMapper om) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : entries(name, description, priceTableJson, blocksJson, om)) out.put(e.key(), e.source());
        return out;
    }

    /** 번역할 글 전부 (순서 = 번역 화면 행 순서). 빈 칸은 뺀다 */
    public static List<Entry> entries(String name, String description, String priceTableJson, String blocksJson, ObjectMapper om) {
        List<Entry> out = new ArrayList<>();
        add(out, "name", "basic", "name", name);
        add(out, "description", "basic", "description", description);

        JsonNode pt = parse(priceTableJson, om);
        add(out, "pt.title", "price", "priceTitle", text(pt, "title"));
        // 숙박은 요금 기준·플랜 대신 객실 타입을 쓴다 (FE 와 같은 규칙: roomTypes 가 있으면 categories/plans 는 뺀다)
        if (pt.path("roomTypes").size() == 0) {
            for (JsonNode c : pt.path("categories")) add(out, "pt.cat." + text(c, "id"), "price", "category", text(c, "name"));
            for (JsonNode p : pt.path("plans")) add(out, "pt.plan." + text(p, "id"), "price", "plan", text(p, "name"));
        }
        for (JsonNode r : pt.path("roomTypes")) add(out, "pt.room." + text(r, "id"), "price", "room", text(r, "name"));
        add(out, "pt.text", "price", "priceText", text(pt, "priceText"));

        int i = 0;
        for (JsonNode b : parse(blocksJson, om)) {
            String k = "b." + text(b, "id");
            Integer sec = i++;
            add(out, k + ".title", sec, "blockTitle", text(b, "title"));
            add(out, k + ".text", sec, "blockText", text(b, "text"));
            for (JsonNode g : b.path("priceGroups")) {
                String gk = k + ".g." + text(g, "id");
                add(out, gk + ".title", sec, "groupTitle", text(g, "title"));
                for (JsonNode c : g.path("columns")) add(out, gk + ".col." + text(c, "id"), sec, "column", text(c, "name"));
                for (JsonNode o : g.path("options")) add(out, gk + ".opt." + text(o, "id"), sec, "option", text(o, "name"));
            }
            for (JsonNode c : b.path("choiceColumns")) add(out, k + ".ccol." + text(c, "id"), sec, "column", text(c, "name"));
            for (JsonNode ch : b.path("choices")) {
                String chk = k + ".ch." + text(ch, "id");
                add(out, chk, sec, "choice", text(ch, "name"));
                JsonNode values = ch.path("values");
                for (Iterator<Map.Entry<String, JsonNode>> it = values.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    add(out, chk + ".v." + e.getKey(), sec, "choiceValue", e.getValue().isTextual() ? e.getValue().asText() : "");
                }
            }
        }
        return out;
    }

    /** 블록 제목 목록 (번역 화면의 묶음 이름용) */
    public static List<String> blockTitles(String blocksJson, ObjectMapper om) {
        List<String> out = new ArrayList<>();
        for (JsonNode b : parse(blocksJson, om)) out.add(text(b, "title"));
        return out;
    }

    /** 번역이 없거나(missing) 번역할 때의 원문이 지금과 다른(stale) 키 */
    public record Outdated(List<String> missing, List<String> stale) {}

    public static Outdated outdated(Map<String, String> current, Map<String, String> translatedFrom, Map<String, String> texts) {
        List<String> missing = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> e : current.entrySet()) {
            String t = texts.get(e.getKey());
            if (t == null || t.isBlank()) missing.add(e.getKey());
            else if (!e.getValue().equals(translatedFrom.get(e.getKey()))) stale.add(e.getKey());
        }
        return new Outdated(missing, stale);
    }

    /** jsonb 문자열(키 → 글) → Map. 비었거나 객체가 아니면 빈 Map */
    public static Map<String, String> asMap(String json, ObjectMapper om) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode n = parse(json, om);
        if (!n.isObject()) return out;
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isTextual()) out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    public static String toJson(Map<String, String> map, ObjectMapper om) {
        try {
            return om.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void add(List<Entry> out, String key, Object section, String field, String source) {
        if (source != null && !source.trim().isEmpty()) out.add(new Entry(key, section, field, source));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? "" : v.asText();
    }

    private static JsonNode parse(String json, ObjectMapper om) {
        if (json == null || json.isBlank()) return om.createObjectNode();
        try {
            JsonNode n = om.readTree(json);
            return n == null || n.isNull() ? om.createObjectNode() : n;
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }
}
