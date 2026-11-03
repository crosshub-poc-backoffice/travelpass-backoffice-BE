package com.crosshub.backoffice.product.translation;

import com.crosshub.backoffice.ai.SolarClient;
import com.crosshub.backoffice.ai.SolarException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Solar 로 글을 번역·언어 감지한다. FE lib/api/translate.server.ts 의 translateMany/detectLanguage 포트.
 * 언어 코드(ko, en, ja, zh-CN, zh-TW, vi)를 그대로 받아 여러 글을 한 번에 번역한다.
 *
 * 키가 없을 때: app.upstage.fake-when-missing(기본 true, 로컬 개발용)이면 "[en] 원문" 가짜 번역·글자 종류로 대충 감지
 * (FE 가 로컬에서 하던 것과 같다). 배포에서는 UPSTAGE_FAKE_WHEN_MISSING=false 로 꺼서 키가 빠진 걸 조용히 넘기지 않게 한다.
 */
@Service
public class TranslateService {

    private static final Logger log = LoggerFactory.getLogger(TranslateService.class);

    public static class TranslateException extends RuntimeException {
        public TranslateException(String message) { super(message); }
        public TranslateException(String message, Throwable cause) { super(message, cause); }
    }

    // 한 요청에 너무 많이 넣으면 순서가 밀리거나 잘리기 쉬워서 작게 나눈다
    static final int BATCH_COUNT = 30;
    static final int BATCH_CHARS = 4000;
    /** 여러 언어를 한 번에 받을 때의 묶음 상한(원문 글자 수). 응답이 원문 × 언어 수만큼 길어지므로 출력이 잘리지 않게 작게 둔다 */
    static final int MULTI_BATCH_CHARS = 1500;

    private static final Pattern HANGUL = Pattern.compile("[가-힣]");
    private static final Pattern KANA = Pattern.compile("[ぁ-ゟ゠-ヿ]");
    private static final Pattern HAN = Pattern.compile("[一-鿿]");

    private final SolarClient solar;
    private final ObjectMapper om;
    private final boolean fakeWhenMissing;

    public TranslateService(SolarClient solar, ObjectMapper om,
                            @Value("${app.upstage.fake-when-missing}") boolean fakeWhenMissing) {
        this.solar = solar;
        this.om = om;
        this.fakeWhenMissing = fakeWhenMissing;
        if (!solar.configured()) {
            if (fakeWhenMissing) log.warn("UPSTAGE_API_KEY 가 없어 상품 번역을 \"[언어] 원문\" 가짜 값으로 채웁니다 (로컬 개발용). 배포에선 키를 넣거나 UPSTAGE_FAKE_WHEN_MISSING=false");
            else log.warn("UPSTAGE_API_KEY 가 없어 상품 번역이 실패합니다 (가짜 번역 꺼짐)");
        }
    }

    private boolean devFallback() {
        return !solar.configured() && fakeWhenMissing;
    }

    /** texts 를 target 언어로. 결과는 같은 순서. source 를 모르면(null) 모델이 알아서 판단한다 */
    public List<String> translateMany(List<String> texts, String target, String source) {
        if (texts.isEmpty()) return List.of();
        if (devFallback()) return texts.stream().map(t -> "[" + target + "] " + t).toList();

        List<String> out = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        int chars = 0;
        for (String t : texts) {
            if (batch.size() >= BATCH_COUNT || (!batch.isEmpty() && chars + t.length() > BATCH_CHARS)) {
                out.addAll(translateBatch(batch, target, source));
                batch = new ArrayList<>();
                chars = 0;
            }
            batch.add(t);
            chars += t.length();
        }
        if (!batch.isEmpty()) out.addAll(translateBatch(batch, target, source));
        return out;
    }

    private List<String> translateBatch(List<String> texts, String target, String source) {
        String to = Locales.NAME.getOrDefault(target, target);
        String from = source == null ? null : Locales.NAME.getOrDefault(source, source);
        String system = String.join("\n",
                "You are a professional translator for a Japan travel product catalog. Translate each string "
                        + (from != null ? "from " + from + " " : "") + "into " + to + ".",
                "Return {\"translations\": [...]} with exactly one translation per input string, in the same order.",
                "Keep line breaks, numbers, prices, times, URLs and proper nouns as they are. Do not add explanations.",
                "If a string is already in " + to + ", return it unchanged.");

        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode arr = props.putObject("translations");
        arr.put("type", "array");
        arr.putObject("items").put("type", "string");
        schema.putArray("required").add("translations");
        schema.put("additionalProperties", false);

        JsonNode data;
        try {
            data = solar.call(system, om.valueToTree(texts).toString(), "translations", schema);
        } catch (SolarException e) {
            throw new TranslateException("번역 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        JsonNode translations = data.path("translations");
        if (!translations.isArray() || translations.size() != texts.size()) {
            log.error("Upstage 번역 개수 불일치: 보낸 {}개, 받은 {}개", texts.size(), translations.size());
            throw new TranslateException("번역 결과가 올바르지 않습니다. 다시 시도해 주세요.");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode t : translations) out.add(t.asText());
        return out;
    }

    /**
     * texts 를 여러 언어로 한 번에. 결과는 언어 → (texts 와 같은 순서의 번역).
     * 언어마다 따로 부르면 같은 원문을 언어 수만큼 다시 보낸다 — 원문은 한 번만 보내고 칸만 언어 수만큼 받는다 (호출 5번 → 묶음 수만큼).
     * 묶음은 원문 MULTI_BATCH_CHARS 자 기준. 한 묶음이 실패하면 그 묶음만 실패한다
     */
    public Map<String, List<String>> translateAll(List<String> texts, List<String> targets, String source) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        targets.forEach(t -> out.put(t, new ArrayList<>()));
        if (texts.isEmpty() || targets.isEmpty()) return out;
        if (devFallback()) {
            for (String t : targets) texts.forEach(x -> out.get(t).add("[" + t + "] " + x));
            return out;
        }
        List<String> batch = new ArrayList<>();
        int chars = 0;
        for (String t : texts) {
            if (batch.size() >= BATCH_COUNT || (!batch.isEmpty() && chars + t.length() > MULTI_BATCH_CHARS)) {
                appendAll(out, translateMultiBatch(batch, targets, source));
                batch = new ArrayList<>();
                chars = 0;
            }
            batch.add(t);
            chars += t.length();
        }
        if (!batch.isEmpty()) appendAll(out, translateMultiBatch(batch, targets, source));
        return out;
    }

    private static void appendAll(Map<String, List<String>> out, Map<String, List<String>> part) {
        part.forEach((locale, list) -> out.get(locale).addAll(list));
    }

    private Map<String, List<String>> translateMultiBatch(List<String> texts, List<String> targets, String source) {
        String from = source == null ? null : Locales.NAME.getOrDefault(source, source);
        String langs = String.join(", ", targets.stream().map(t -> "\"" + t + "\" = " + Locales.NAME.getOrDefault(t, t)).toList());
        // 고정 문구를 앞에, 바뀌는 언어 목록을 뒤에 — 앞부분이 같을수록 캐시가 듣는다
        String system = String.join("\n",
                "You are a professional translator for a Japan travel product catalog.",
                "Return {\"items\": [...]} with exactly one item per input string, in the same order. Each item has one field per target language.",
                "Keep line breaks, numbers, prices, times, URLs and proper nouns as they are. Do not add explanations.",
                "If a string is already in a target language, return it unchanged for that language.",
                "Translate each string " + (from != null ? "from " + from + " " : "") + "into: " + langs + ".");

        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode items = schema.putObject("properties").putObject("items");
        items.put("type", "array");
        ObjectNode item = items.putObject("items");
        item.put("type", "object");
        ObjectNode props = item.putObject("properties");
        ArrayNode required = item.putArray("required");
        for (String t : targets) {
            props.putObject(t).put("type", "string");
            required.add(t);
        }
        item.put("additionalProperties", false);
        schema.putArray("required").add("items");
        schema.put("additionalProperties", false);

        JsonNode data;
        try {
            data = solar.call(system, om.valueToTree(texts).toString(), "translations", schema);
        } catch (SolarException e) {
            throw new TranslateException("번역 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        JsonNode got = data.path("items");
        if (!got.isArray() || got.size() != texts.size()) {
            log.error("Upstage 번역 개수 불일치: 보낸 {}개, 받은 {}개", texts.size(), got.size());
            throw new TranslateException("번역 결과가 올바르지 않습니다. 다시 시도해 주세요.");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String t : targets) {
            List<String> list = new ArrayList<>();
            for (JsonNode g : got) {
                JsonNode v = g.path(t);
                if (!v.isTextual()) {
                    log.error("Upstage 번역에 {} 칸이 없음", t);
                    throw new TranslateException("번역 결과가 올바르지 않습니다. 다시 시도해 주세요.");
                }
                list.add(v.asText());
            }
            out.put(t, list);
        }
        return out;
    }

    /** 글의 언어 코드(ko, en, ja, zh-CN …). 모르면 null */
    public String detectLanguage(String text) {
        if (text == null || text.isBlank()) return null;
        if (devFallback()) {
            if (HANGUL.matcher(text).find()) return "ko";
            if (KANA.matcher(text).find()) return "ja";
            if (HAN.matcher(text).find()) return "zh-CN";
            return "en";
        }
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode lang = schema.putObject("properties").putObject("language");
        lang.put("type", "string");
        ArrayNode en = lang.putArray("enum");
        Locales.ALL.forEach(en::add);
        en.add("other");
        schema.putArray("required").add("language");
        schema.put("additionalProperties", false);

        JsonNode data;
        try {
            data = solar.call("Identify the main language of the text. Answer \"other\" if it is none of the listed languages.",
                    text.length() > 1000 ? text.substring(0, 1000) : text, "language", schema);
        } catch (SolarException e) {
            throw new TranslateException("번역 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        String code = data.path("language").asText();
        return Locales.ALL.contains(code) ? code : null;
    }
}
