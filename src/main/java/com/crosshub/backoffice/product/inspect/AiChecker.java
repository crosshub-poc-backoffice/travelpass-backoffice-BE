package com.crosshub.backoffice.product.inspect;

import com.crosshub.backoffice.ai.SolarClient;
import com.crosshub.backoffice.ai.SolarException;
import com.crosshub.backoffice.product.inspect.InspectTarget.CancelRule;
import com.crosshub.backoffice.product.inspect.InspectTarget.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Solar 에게 상품 본문을 보여주고 항목별로 판정을 받는다. FE lib/server/inspect/ai/* 포트.
 * 설계 원칙 (FE docs/상품검수-AI-파이프라인-설계.md):
 *  1. 자유 서술 금지 — 항목마다 ok/issue/unsure 와 근거 인용만 받는다
 *  2. 근거 인용이 원문에 실제로 없으면 그 지적을 버린다 (환각 차단)
 *  3. unsure 를 허용한다 — 억지로 한쪽을 고르게 하면 오탐이 는다
 *  4. 항목 전부를 한 번에 묻는다 (항목마다 부르면 비용·지연이 항목 수만큼 곱해진다)
 */
@Component
public class AiChecker implements Checker {

    private static final Logger log = LoggerFactory.getLogger(AiChecker.class);
    /** 긴 상품은 앞쪽만 본다. 토큰이 곧 비용이고 지연이다 */
    static final int MAX_BODY_CHARS = 6000;

    record Question(String key, String code, String severity, String field, String criteria) {}

    static final List<Question> QUESTIONS = List.of(
            new Question("text_field_mismatch", "TEXT_FIELD_MISMATCH", "high", "description", String.join(" ",
                    "[본문 ↔ 입력값 불일치]",
                    "아래 \"입력된 값\"과 본문의 서술이 서로 다른 말을 하는 곳을 찾아라.",
                    "예: 입력된 취소규정은 \"3일 전 50%\"인데 본문에 \"3일 전까지 무료 취소\"라고 적힌 경우.",
                    "예: 입력된 성인 요금이 15,000엔인데 본문에 \"대인 12,000엔\"이라고 적힌 경우.",
                    "예: 입력된 예약 마감이 1일 전인데 본문에 \"당일 예약 가능\"이라고 적힌 경우.",
                    "같은 내용을 다르게 표현한 것(예: \"180분\"과 \"3시간\")은 불일치가 아니다.",
                    "본문에 그런 서술 자체가 없으면 ok다. 없는 것은 불일치가 아니다.")),
            new Question("external_contact", "EXTERNAL_CONTACT", "high", "description", String.join(" ",
                    "[플랫폼 우회 유도]",
                    "본문이 이 플랫폼을 거치지 않고 업체에 직접 예약·결제하도록 유도하는지 보라.",
                    "예: \"예약은 저희 홈페이지에서\", \"전화로 문의주시면 할인\", 외부 예약 사이트 주소.",
                    "집합 장소 안내, 현장 비상 연락처, 업체 소개 목적의 주소는 정상이다 — ok로 본다.")));

    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "tour", "관광·체험", "lodging", "숙박", "transport", "교통", "package", "패키지", "other", "기타");

    private final SolarClient solar;
    private final ObjectMapper om;

    public AiChecker(SolarClient solar, ObjectMapper om) {
        this.solar = solar;
        this.om = om;
    }

    @Override public String code() { return "AI"; }
    @Override public boolean isAi() { return true; }

    @Override
    public boolean appliesTo(InspectTarget t) {
        return solar.configured() && !renderBody(t.proposed()).trim().isEmpty();
    }

    @Override
    public List<Finding> run(InspectTarget t) {
        String body = renderBody(t.proposed());
        String user = "[입력된 값]\n" + renderFields(t.proposed()) + "\n\n[상품 본문]\n" + body;
        JsonNode answers;
        try {
            answers = solar.call(systemPrompt(), user, "product_inspection", schema());
        } catch (SolarException e) {
            // AI 가 실패해도 규칙 결과는 살아야 한다 — 여기서 삼키고 빈 목록
            log.error("[inspect] Solar 검수 실패: {}", e.getMessage());
            return List.of();
        }
        String haystack = squash(body);
        List<Finding> out = new ArrayList<>();
        for (Question q : QUESTIONS) {
            JsonNode a = answers.path(q.key());
            String status = a.path("status").asText("ok");
            if ("ok".equals(status)) continue;
            String quote = a.path("quote").asText("").trim();
            // 원칙 2 — 원문에 없는 근거는 환각으로 보고 버린다
            if (quote.isEmpty() || !haystack.contains(squash(quote))) continue;
            boolean unsure = "unsure".equals(status);
            out.add(new Finding(q.code(), unsure ? "low" : q.severity(), "ai", q.field(),
                    RuleCheckers.evidence("quote", quote, "note", a.path("note").asText("").trim(), "unsure", unsure)));
        }
        return out;
    }

    /** 모델이 본문과 대조할 "입력된 값" */
    static String renderFields(Snapshot p) {
        List<String> lines = new ArrayList<>();
        lines.add("상품 종류: " + CATEGORY_LABEL.getOrDefault(p.category(), p.category()));
        lines.add("예약 마감: 출발 " + p.deadlineDays() + "일 전 " + p.deadlineTime() + "까지");
        lines.add("예약 확정: " + ("instant".equals(p.confirmType()) ? "즉시 확정" : "업체 견적 후 확정"));
        if (!p.cancelRules().isEmpty()) {
            List<String> rules = new ArrayList<>();
            for (CancelRule r : p.cancelRules()) rules.add("출발 " + r.daysBefore() + "일 전까지 취소 시 수수료 " + r.percent() + "%");
            lines.add("취소 규정: " + String.join(" / ", rules) + " (그 이후 취소·노쇼는 100%)");
        } else {
            lines.add("취소 규정: (미입력)");
        }
        JsonNode pt = p.priceTable();
        NumberFormat nf = NumberFormat.getInstance(Locale.KOREA);
        for (JsonNode plan : pt.path("plans")) {
            List<String> cells = new ArrayList<>();
            for (JsonNode c : pt.path("categories")) {
                JsonNode v = plan.path("prices").path(RuleCheckers.text(c, "id"));
                if (v.isNumber()) cells.add(RuleCheckers.text(c, "name") + " " + nf.format(v.asLong()) + "엔");
            }
            String name = RuleCheckers.text(plan, "name");
            if (!cells.isEmpty()) lines.add("요금 [" + (name.isEmpty() ? "이름 없음" : name) + "]: " + String.join(", ", cells));
        }
        for (JsonNode room : pt.path("roomTypes")) {
            JsonNode rate = pt.path("roomPricing").path("base").path(RuleCheckers.text(room, "id"));
            if (rate.path("price").isNumber()) {
                lines.add("객실 요금 [" + RuleCheckers.text(room, "name") + "]: " + nf.format(rate.path("price").asLong()) + "엔");
            }
        }
        String priceText = RuleCheckers.text(pt, "priceText").trim();
        if (!priceText.isEmpty()) lines.add("요금 참고: " + priceText);
        return String.join("\n", lines);
    }

    /** 모델이 읽을 본문 — 이 글 안에서만 근거를 인용하게 한다 */
    static String renderBody(Snapshot p) {
        List<String> parts = new ArrayList<>();
        parts.add("상품명: " + p.name());
        if (!p.description().trim().isEmpty()) parts.add(p.description());
        for (JsonNode b : p.blocks()) {
            String text = RuleCheckers.text(b, "text");
            String title = RuleCheckers.text(b, "title");
            if (!text.trim().isEmpty()) parts.add("[" + (title.isEmpty() ? "섹션" : title) + "]\n" + text);
        }
        String joined = String.join("\n\n", parts);
        return joined.length() > MAX_BODY_CHARS ? joined.substring(0, MAX_BODY_CHARS) : joined;
    }

    static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    static String systemPrompt() {
        List<String> lines = new ArrayList<>(List.of(
                "너는 일본 여행 상품 카탈로그의 심사 보조다. 아래 항목만 판정한다.",
                "각 항목에 status(ok/issue/unsure), quote, note를 채워라.",
                "- quote는 [상품 본문]에서 한 글자도 바꾸지 말고 그대로 옮긴다. 요약·의역 금지.",
                "- 본문에서 그대로 인용할 근거를 찾을 수 없으면 status는 ok로 한다. 추측으로 issue를 만들지 마라.",
                "- 판단이 애매하면 issue가 아니라 unsure를 쓴다.",
                "- note는 한국어 한 문장. 근거가 없으면 빈 문자열.",
                ""));
        for (Question q : QUESTIONS) lines.add(q.key() + ": " + q.criteria());
        return String.join("\n", lines);
    }

    /** strict JSON schema. 항목마다 status·quote·note 세 칸 */
    JsonNode schema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (Question q : QUESTIONS) {
            ObjectNode item = props.putObject(q.key());
            item.put("type", "object");
            ObjectNode ip = item.putObject("properties");
            ObjectNode status = ip.putObject("status");
            status.put("type", "string");
            status.putArray("enum").add("ok").add("issue").add("unsure");
            ip.putObject("quote").put("type", "string").put("description", "본문에서 그대로 옮긴 근거 문구. 없으면 빈 문자열");
            ip.putObject("note").put("type", "string").put("description", "무엇이 어긋나는지 한 문장. 없으면 빈 문자열");
            item.putArray("required").add("status").add("quote").add("note");
            item.put("additionalProperties", false);
        }
        var req = schema.putArray("required");
        for (Question q : QUESTIONS) req.add(q.key());
        schema.put("additionalProperties", false);
        return schema;
    }
}
