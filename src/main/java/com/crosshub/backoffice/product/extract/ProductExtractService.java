package com.crosshub.backoffice.product.extract;

import com.crosshub.backoffice.ai.DocumentParseClient;
import com.crosshub.backoffice.ai.SolarClient;
import com.crosshub.backoffice.ai.SolarException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 상품 소개서(파일·URL 을 인쇄한 PDF) → 등록 화면 초안. "Upstage 는 읽고 해석만, 통제와 검증은 코드가, 저장은 사람이".
 *
 *  1) Document Parse — OCR·레이아웃 인식으로 문서를 markdown 으로 (머리말·꼬리말 제외)
 *  2) DocumentCleaner — 끊긴 줄·장식 기호·중복 줄 정리
 *  3) Solar — strict JSON Schema 로 칸 채우기. 사실 필드는 근거(evidence)를 먼저 복사하고 값을 꺼내게,
 *     문장 필드(설명·섹션)는 사실만으로 새로 쓰게 한다 (resources/upstage/product-solar-schema.json, product-extract-prompt.txt)
 *  4) ExtractCheck — 결정적 검산(grounding·금액/날짜 재계산)과 문장 lint. 문제가 있으면 문장으로 돌려주고 최대 2번 다시 요청
 *  5) 그래도 틀린 항목은 버린다 (틀린 값보다 빈칸이 안전하다)
 *
 * 저장은 하지 않는다 (stateless). 결과는 FE lib/products/extract-map.ts 가 받는 평면 모양으로 바꿔 돌려준다.
 * 키가 없고 app.upstage.fake-when-missing 이면(로컬 개발) 크레딧을 쓰지 않는 샘플(product-extract-sample.json)을 돌려준다.
 */
@Service
public class ProductExtractService {

    private static final Logger log = LoggerFactory.getLogger(ProductExtractService.class);
    static final int MAX_REPAIRS = 2;

    public static class ExtractException extends RuntimeException {
        public ExtractException(String message) { super(message); }
    }

    private final DocumentParseClient parser;
    private final SolarClient solar;
    private final ObjectMapper om;
    private final boolean fakeWhenMissing;
    private final JsonNode schema;
    private final JsonNode sample;
    private final String prompt;
    private final JsonNode labelSchema;
    private final String labelPrompt;

    public ProductExtractService(DocumentParseClient parser, SolarClient solar, ObjectMapper om,
                                 @Value("${app.upstage.fake-when-missing}") boolean fakeWhenMissing) throws IOException {
        this.parser = parser;
        this.solar = solar;
        this.om = om;
        this.fakeWhenMissing = fakeWhenMissing;
        this.schema = om.readTree(resource("upstage/product-solar-schema.json"));
        this.sample = om.readTree(resource("upstage/product-extract-sample.json"));
        this.prompt = resource("upstage/product-extract-prompt.txt");
        this.labelSchema = om.readTree(resource("upstage/block-label-schema.json"));
        this.labelPrompt = resource("upstage/block-label-prompt.txt");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 키가 없어 샘플로 돌고 있는지. 화면이 "이건 진짜 추출이 아니다"를 알리는 데 쓴다 */
    public boolean usingSample() {
        return (!parser.configured() || !solar.configured()) && fakeWhenMissing;
    }

    public JsonNode extract(byte[] bytes, String filename) {
        if (usingSample()) return sample.deepCopy();
        requireKey();
        String doc;
        try {
            doc = DocumentCleaner.clean(parser.parse(bytes, filename));
        } catch (DocumentParseClient.ParseException e) {
            throw new ExtractException(e.getMessage());
        }
        return run(doc);
    }

    /**
     * URL 로 읽은 페이지 → 초안. 글자는 DOM 그대로 쓰고, 본문 이미지만 OCR 해서 [[IMG n]] 자리에 끼워 넣는다.
     * DOM 글자가 거의 없던 페이지(pagePdf)는 예전처럼 페이지 전체 인쇄본을 OCR 한다
     */
    public JsonNode extractPage(UrlSnapshotter.PageCapture page) {
        if (usingSample()) return sample.deepCopy();
        if (page.pagePdf() != null) return extract(page.pagePdf(), "page.pdf");
        requireKey();
        Map<Integer, String> ocr = Map.of();
        if (page.imagesPdf() != null) {
            try {
                ocr = parser.parsePages(page.imagesPdf(), "images.pdf");
            } catch (DocumentParseClient.ParseException e) {
                // 이미지를 못 읽어도 글자만으로 계속한다. 이미지 속 요금표가 빠지면 누락 검사·확인 필요 목록에 드러난다
                log.warn("페이지 이미지 OCR 실패, 글자만으로 추출합니다: {}", e.getMessage());
            }
        }
        return run(DocumentCleaner.clean(assemble(page.markdown(), ocr)));
    }

    private static final java.util.regex.Pattern IMG = java.util.regex.Pattern.compile("^\\[\\[IMG (\\d+)]]$");

    /** [[IMG n]] 줄 → "[image n]" + 그 이미지의 OCR 글자 + "[/image n]" (블록 경계). 글자가 없는 이미지(사진)는 줄을 지운다 */
    static String assemble(String markdown, Map<Integer, String> ocr) {
        StringBuilder sb = new StringBuilder();
        for (String line : (markdown == null ? "" : markdown).split("\n", -1)) {
            java.util.regex.Matcher m = IMG.matcher(line.strip());
            if (m.matches()) {
                String text = ocr.getOrDefault(Integer.parseInt(m.group(1)), "").strip();
                if (text.isEmpty()) continue;
                sb.append("\n[image ").append(m.group(1)).append("]\n").append(text).append("\n[/image ").append(m.group(1)).append("]\n\n");
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private void requireKey() {
        if (!parser.configured() || !solar.configured()) throw new ExtractException("UPSTAGE_API_KEY가 설정되지 않았습니다.");
    }

    /**
     * 정리된 문서 → 초안.
     *  1) 블록으로 나누고 Solar 가 블록마다 라벨을 붙인다. 무관한 블록(메뉴·다른 상품·연락처)은 뺀다
     *  2) 라벨이 붙은 블록을 주고 추출. 항목마다 출처 블록을 적게 한다
     *  3) 검산(출처 블록·표 칸 결합·누락 블록) → 최대 2번 재요청 → 남은 틀린 항목은 버린다
     */
    JsonNode run(String doc) {
        if (doc.isBlank()) throw new ExtractException("문서에서 글자를 찾지 못했습니다.");
        List<Blocks.Block> blocks = Blocks.split(doc);
        try {
            Map<String, String> labels = classify(blocks);
            List<Blocks.Block> kept = new ArrayList<>();
            for (Blocks.Block b : blocks) if (!"irrelevant".equals(labels.get(b.id()))) kept.add(b);
            if (kept.isEmpty()) kept = blocks;
            ExtractCheck.Ctx ctx = ExtractCheck.Ctx.of(kept, labels);
            log.info("상품 추출: 블록 {}개 중 {}개 사용, 라벨 {}", blocks.size(), kept.size(), labels);

            // system(고정) → 문서 → (실패한 답 → 고칠 점)… 순서. 바뀌지 않는 앞부분이 길수록 캐시가 듣는다
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(SolarClient.message("system", prompt));
            messages.add(SolarClient.message("user", ctx.doc()));
            ObjectNode draft = ask(messages, "product_draft", schema);
            for (int attempt = 0; attempt < MAX_REPAIRS; attempt++) {
                List<String> problems = ExtractCheck.problems(draft, ctx);
                if (problems.isEmpty()) break;
                log.info("상품 추출 검산 실패 {}건, 재요청 {}회차: {}", problems.size(), attempt + 1, problems);
                messages.add(SolarClient.message("assistant", draft.toString()));
                messages.add(SolarClient.message("user", repairRequest(problems)));
                draft = ask(messages, "product_draft", schema);
            }
            int dropped = ExtractCheck.dropInvalid(draft, ctx);
            if (dropped > 0) log.info("상품 추출: 검산을 통과하지 못한 {}개 항목을 비웠습니다", dropped);
            return toFlat(draft, ctx);
        } catch (SolarException e) {
            throw new ExtractException("문서를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 블록 → 라벨. 분류가 실패하면 빈 맵(블록을 거르지 않고 누락 검사도 하지 않는다 — 예전과 같은 동작).
     *  모델이 빠뜨린 블록은 "notice" 로 둔다 — 내용 블록으로 보고 누락 검사 대상에 넣는 쪽이 안전하다 */
    Map<String, String> classify(List<Blocks.Block> blocks) {
        List<Map<String, String>> messages = List.of(
                SolarClient.message("system", labelPrompt),
                SolarClient.message("user", Blocks.render(blocks)));
        try {
            return labels(blocks, ask(messages, "block_labels", labelSchema));
        } catch (SolarException e) {
            log.warn("블록 분류 실패, 라벨 없이 추출합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    static Map<String, String> labels(List<Blocks.Block> blocks, JsonNode res) {
        Map<String, String> got = new java.util.HashMap<>();
        for (JsonNode b : res.path("blocks")) got.put(b.path("id").asText("").strip(), b.path("label").asText("").strip());
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Blocks.Block b : blocks) {
            String label = got.getOrDefault(b.id(), "");
            out.put(b.id(), label.isEmpty() ? "notice" : label);
        }
        return out;
    }

    private ObjectNode ask(List<Map<String, String>> messages, String name, JsonNode schema) {
        JsonNode res = solar.call(messages, name, schema);
        if (!(res instanceof ObjectNode o)) throw new SolarException("응답이 객체가 아닙니다.");
        return o;
    }

    static String repairRequest(List<String> problems) {
        StringBuilder sb = new StringBuilder("Your answer failed these checks. Fix only these points and return the whole JSON again. "
                + "If a value cannot be fixed from the document, leave it empty.\n");
        for (String p : problems) sb.append("- ").append(p).append('\n');
        return sb.toString();
    }

    /** 소인·어린이를 가리키는 말 (문서 언어 몇 가지). 요금표에 이 열이 빠졌는지 보는 데 쓴다 */
    private static final java.util.regex.Pattern CHILD = java.util.regex.Pattern.compile(
            "小人|子供|子ども|こども|小児|幼児|児童|儿童|兒童|소인|아동|어린이|유아|(?i:child|kid|infant)|trẻ em");

    /** 요금표가 있는데 문서가 말하는 소인 요금 열이 없다 — 이미지의 괄호 속 작은 글씨를 OCR 이 놓친 흔한 경우 */
    static boolean missingChildPrice(JsonNode d, String doc) {
        JsonNode rows = d.path("priceRows");
        if (rows.isEmpty() || doc == null || !CHILD.matcher(doc).find()) return false;
        for (JsonNode r : rows) if (CHILD.matcher(r.path("category").asText("")).find()) return false;
        return true;
    }

    ObjectNode toFlat(JsonNode d) {
        return toFlat(d, ExtractCheck.Ctx.of(""));
    }

    /** Solar 답(근거 포함) → 화면이 받는 평면 모양. 근거는 검산에만 쓰고 여기서 버린다.
     *  missingChildPrice·uncoveredBlocks 는 화면의 "확인 필요" 목록에 쓴다 (나머지 빈칸은 화면이 요금표·취소 규정을 보고 직접 찾는다) */
    ObjectNode toFlat(JsonNode d, ExtractCheck.Ctx ctx) {
        ObjectNode out = om.createObjectNode();
        String locale = d.path("sourceLocale").asText("");
        out.put("sourceLocale", "other".equals(locale) ? "" : locale);
        out.put("productName", d.path("productName").asText("").strip());
        out.put("description", description(d.path("description")));
        ArrayNode rows = out.putArray("priceRows");
        for (JsonNode r : d.path("priceRows")) {
            ObjectNode row = rows.addObject();
            row.put("plan", r.path("plan").asText("").strip());
            row.put("category", r.path("category").asText("").strip());
            row.put("yen", Math.round(r.path("amount").asDouble()));
        }
        out.put("priceNote", priceNote(d));
        ArrayNode adjustments = out.putArray("adjustments");
        for (JsonNode a : d.path("adjustments")) {
            adjustments.addObject()
                    .put("kind", a.path("kind").asText(""))
                    .put("label", a.path("label").asText("").strip())
                    .put("category", a.path("category").asText("").strip())
                    .put("yen", Math.round(a.path("amount").asDouble()));
        }
        out.put("saleStart", d.path("salePeriod").path("start").asText(""));
        out.put("saleEnd", d.path("salePeriod").path("end").asText(""));
        ArrayNode rules = out.putArray("cancelRules");
        for (JsonNode r : d.path("cancelRules")) {
            rules.addObject().put("daysBefore", r.path("daysBefore").asInt()).put("percent", r.path("percent").asDouble());
        }
        out.put("missingChildPrice", missingChildPrice(d, ctx.doc()));
        // 재요청 뒤에도 어느 칸에도 쓰이지 않은 내용 블록 — 사람이 원문을 보고 직접 옮기게 라벨과 앞부분을 보여 준다
        ArrayNode uncovered = out.putArray("uncoveredBlocks");
        for (Blocks.Block b : ExtractCheck.uncovered(d, ctx)) {
            uncovered.addObject().put("label", ctx.label(b.id())).put("snippet", Blocks.snippet(b));
        }
        ArrayNode sections = out.putArray("sections");
        String itinerary = itinerary(d.path("itinerary"));
        if (!itinerary.isEmpty()) sections.addObject().put("kind", "itinerary").put("title", "").put("text", itinerary);
        for (JsonNode s : d.path("sections")) {
            sections.addObject()
                    .put("kind", s.path("kind").asText(""))
                    .put("title", s.path("title").asText("").strip())
                    .put("text", s.path("text").asText("").strip());
        }
        return out;
    }

    /** 참고 사항 = 모델이 모은 원문 + 할인·할증 근거 문장(이미 들어 있지 않은 것만). 조성금 문장이 설명으로만 가고 요금 쪽에서 빠지는 것을 막는다 */
    static String priceNote(JsonNode d) {
        StringBuilder sb = new StringBuilder(d.path("priceNote").asText("").strip());
        String have = ExtractCheck.canon(sb.toString());
        for (JsonNode a : d.path("adjustments")) {
            String evidence = a.path("evidence").asText("").strip();
            String key = ExtractCheck.canon(evidence);
            if (key.isEmpty() || have.contains(key)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(evidence);
            have += key;
        }
        return sb.toString();
    }

    /** 일정은 다시 쓰지 않고 날짜별 원문을 코드가 줄로 이어 붙인다 — 순서가 꼬일 여지를 없앤다 */
    static String itinerary(JsonNode days) {
        List<String> lines = new ArrayList<>();
        for (JsonNode day : days) {
            String label = day.path("day").asText("").strip();
            String text = day.path("text").asText("").strip();
            if (text.isEmpty()) continue;
            lines.add(label.isEmpty() ? text : label + ": " + text);
        }
        return String.join("\n", lines);
    }

    /** 설명은 모델이 칸(summary·highlights)만 채우고 모양은 코드가 정한다 — 어느 상품이든 같은 형식이 된다 */
    static String description(JsonNode d) {
        String summary = d.path("summary").asText("").strip();
        List<String> bullets = new ArrayList<>();
        for (JsonNode h : d.path("highlights")) {
            String text = h.asText("").strip();
            if (!text.isEmpty()) bullets.add("• " + text);
        }
        if (bullets.isEmpty()) return summary;
        return (summary.isEmpty() ? "" : summary + "\n\n") + String.join("\n", bullets);
    }
}
