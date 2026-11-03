package com.crosshub.backoffice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Upstage Document Parse — PDF·이미지·오피스 문서를 글자(markdown)로 바꾼다. OCR·표 구조·레이아웃 인식을 여기서 한다.
 * 뜻을 읽어 칸을 채우는 건 Solar 가 하고, 이 클라이언트는 "읽기"만 한다 (상품 추출 1단계).
 *
 * - ocr=force: 웹페이지를 인쇄한 PDF 는 글자 층이 있어도 요금표·일정표가 이미지인 경우가 많다. 글자 층만 믿으면 가격이 빠진다.
 * - 응답의 elements 마다 붙는 category(heading1·paragraph·table·figure·header·footer …)는 사이트 형식과 무관한 구조 표시다.
 *   머리말·꼬리말은 버린다 — 사이트 메뉴·연락처가 상품 정보로 섞이는 걸 막고 Solar 입력 토큰도 줄인다.
 * - 과금은 페이지 단위다 (standard). 키는 문서용 키가 있으면 그걸, 없으면 공용 키를 쓴다.
 */
@Component
public class DocumentParseClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseClient.class);
    static final String ENDPOINT = "https://api.upstage.ai/v1/document-digitization";
    private static final Set<String> DROP = Set.of("header", "footer");
    private static final long[] RETRY_WAITS_MS = {2000, 5000};

    public static class ParseException extends RuntimeException {
        public ParseException(String message) { super(message); }
    }

    private final String apiKey;
    private final ObjectMapper om;
    private final RestClient http;

    public DocumentParseClient(@Value("${app.upstage.docs-api-key}") String docsKey,
                               @Value("${app.upstage.api-key}") String apiKey,
                               ObjectMapper om) {
        String key = docsKey == null || docsKey.isBlank() ? apiKey : docsKey;
        this.apiKey = key == null ? "" : key.trim();
        this.om = om;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(300_000);
        this.http = RestClient.builder().baseUrl(ENDPOINT).requestFactory(factory).build();
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** 문서 → markdown (머리말·꼬리말 제외, 문서 순서 그대로) */
    public String parse(byte[] bytes, String filename) {
        return markdown(call(bytes, filename));
    }

    /** 문서 → 쪽 번호(1부터)별 markdown. 이미지들을 한 PDF 에 한 쪽씩 담아 한 번에 읽고, 어느 이미지의 글자인지 되찾을 때 쓴다 */
    public java.util.Map<Integer, String> parsePages(byte[] bytes, String filename) {
        JsonNode root = call(bytes, filename);
        java.util.Map<Integer, StringBuilder> pages = new java.util.TreeMap<>();
        for (JsonNode e : root.path("elements")) {
            if (DROP.contains(e.path("category").asText())) continue;
            String md = elementMarkdown(e);
            if (md.isEmpty()) continue;
            StringBuilder sb = pages.computeIfAbsent(e.path("page").asInt(1), k -> new StringBuilder());
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(md);
        }
        java.util.Map<Integer, String> out = new java.util.TreeMap<>();
        pages.forEach((k, v) -> out.put(k, v.toString()));
        return out;
    }

    private JsonNode call(byte[] bytes, String filename) {
        if (!configured()) throw new ParseException("UPSTAGE_API_KEY가 설정되지 않았습니다.");
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("document", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() { return filename; }
        });
        form.add("model", "document-parse");
        form.add("ocr", "force");
        // 표는 html 로도 받는다 — markdown 표는 병합 칸(rowspan·colspan)이 사라져 어느 행·열의 값인지 잃는다
        form.add("output_formats", "['markdown', 'html']");

        Response res = post(form);
        if (res.status < 200 || res.status >= 300) {
            // 원문 에러(키·쿼터 정보가 섞일 수 있다)는 로그에만
            log.error("Upstage Document Parse 호출 실패 ({}): {}", res.status, res.body);
            if (res.status == 413 || res.status == 415 || res.status == 422) {
                throw new ParseException("문서를 읽을 수 없습니다. 50MB·100페이지 이하의 PDF·오피스·이미지 파일인지 확인해 주세요.");
            }
            throw new ParseException("문서를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        try {
            return om.readTree(res.body);
        } catch (IOException e) {
            log.error("Upstage Document Parse 응답 해석 실패", e);
            throw new ParseException("문서를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 분당 호출 한도에 걸리면(429) 잠깐 쉬고 다시 건다. 기다릴 시간은 Upstage 가 헤더로 알려주면 그걸 따른다 */
    private Response post(MultiValueMap<String, Object> form) {
        for (int attempt = 0; ; attempt++) {
            Response res = http.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .exchange((req, r) -> new Response(r.getStatusCode().value(),
                            new String(r.getBody().readAllBytes(), StandardCharsets.UTF_8),
                            r.getHeaders().getFirst("X-Upstage-RateLimit-Retry-After")));
            if (res.status != 429 || attempt >= RETRY_WAITS_MS.length) return res;
            long wait = RETRY_WAITS_MS[attempt];
            try {
                if (res.retryAfter != null) wait = Math.min(30_000, (long) (Double.parseDouble(res.retryAfter) * 1000));
            } catch (NumberFormatException ignored) {
                // 형식을 모르면 기본 대기
            }
            log.warn("Upstage Document Parse rate limit (429). {}ms 뒤 재시도", wait);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ParseException("문서를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
    }

    /** elements 를 순서대로 이어 붙인다. elements 가 없으면 문서 전체 markdown 을 쓴다 */
    static String markdown(JsonNode root) {
        JsonNode elements = root.path("elements");
        if (elements.isArray() && !elements.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode e : elements) {
                if (DROP.contains(e.path("category").asText())) continue;
                String md = elementMarkdown(e);
                if (md.isEmpty()) continue;
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(md);
            }
            return sb.toString();
        }
        return root.path("content").path("markdown").asText("");
    }

    /** 요소 하나의 markdown. 표는 html 을 병합 칸까지 펼친 격자로 바꾼다 (실패하면 Parse 의 markdown 그대로) */
    static String elementMarkdown(JsonNode e) {
        String md = e.path("content").path("markdown").asText("").trim();
        if ("table".equals(e.path("category").asText())) {
            String html = e.path("content").path("html").asText("");
            String grid = com.crosshub.backoffice.product.extract.TableGrids.markdownFromHtml(html);
            if (!grid.isEmpty()) return grid;
        }
        return md;
    }

    private record Response(int status, String body, String retryAfter) {}
}
