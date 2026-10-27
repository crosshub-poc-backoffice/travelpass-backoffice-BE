package com.crosshub.backoffice.product.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 상품 소개서 → 등록 화면 초안. Upstage Universal Extraction — 파일과 JSON Schema 를 주면 그 모양대로 값을 뽑아준다.
 * FE lib/api/upstage.server.ts 를 옮긴 것. 레이아웃·OCR·표 인식을 이 API 가 안에서 다 하므로 파일을 통째로(base64) 넘긴다
 * — 미리 텍스트로 바꿔서 넘기면 표 병합 정보가 사라진다.
 *
 * 뽑을 값의 모양은 resources/upstage/product-extract-schema.json. 그 안의 description 이 모델에게 보내는 프롬프트이고
 * 정확도를 좌우하는 가장 큰 레버다. 객체의 속성이 "객체의 배열"이면 거부당하므로 중첩은 한 단계로만 유지한다.
 * 결과는 평면 JSON 그대로 돌려주고, 등록 화면 모양으로 바꾸는 건 FE(lib/products/extract-map.ts)가 한다.
 *
 * 키가 없고 app.upstage.fake-when-missing 이면(로컬 개발) 크레딧을 쓰지 않는 샘플(product-extract-sample.json)을 돌려준다.
 */
@Component
public class ProductExtractClient {

    private static final Logger log = LoggerFactory.getLogger(ProductExtractClient.class);
    static final String ENDPOINT = "https://api.upstage.ai/v1/information-extraction/chat/completions";
    /** 동기 호출은 1초에 1번까지다. 업체가 연달아 올리면 429 가 나므로 잠깐 쉬고 다시 건다 */
    private static final long[] RETRY_WAITS_MS = {1500, 4000};

    public static class ExtractException extends RuntimeException {
        public ExtractException(String message) { super(message); }
    }

    private final String apiKey;
    private final boolean fakeWhenMissing;
    private final ObjectMapper om;
    private final JsonNode schema;
    private final JsonNode sample;
    private final RestClient http;

    public ProductExtractClient(@Value("${app.upstage.docs-api-key}") String docsKey,
                                @Value("${app.upstage.api-key}") String apiKey,
                                @Value("${app.upstage.fake-when-missing}") boolean fakeWhenMissing,
                                ObjectMapper om) throws IOException {
        String key = docsKey == null || docsKey.isBlank() ? apiKey : docsKey;
        this.apiKey = key == null ? "" : key.trim();
        this.fakeWhenMissing = fakeWhenMissing;
        this.om = om;
        this.schema = read("upstage/product-extract-schema.json");
        this.sample = read("upstage/product-extract-sample.json");
        // 문서를 읽고 값을 뽑는 데 분 단위가 걸린다
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(300_000);
        this.http = RestClient.builder().baseUrl(ENDPOINT).requestFactory(factory).build();
    }

    private JsonNode read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return om.readTree(in);
        }
    }

    /** 키가 없어 샘플로 돌고 있는지. 화면이 "이건 진짜 추출이 아니다"를 알리는 데 쓴다 — 샘플을 진짜로 오해하면 올리지도 않은 상품이 등록된다 */
    public boolean usingSample() {
        return apiKey.isEmpty() && fakeWhenMissing;
    }

    public JsonNode extract(byte[] bytes, String mime) {
        if (usingSample()) return sample.deepCopy();
        if (apiKey.isEmpty()) throw new ExtractException("UPSTAGE_DOCS_API_KEY(또는 UPSTAGE_API_KEY)가 설정되지 않았습니다.");

        ObjectNode body = om.createObjectNode();
        body.put("model", "information-extract");
        body.set("messages", om.valueToTree(List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes))))))));
        ObjectNode jsonSchema = om.createObjectNode();
        jsonSchema.put("name", "product_draft");
        jsonSchema.set("schema", schema);
        ObjectNode format = om.createObjectNode();
        format.put("type", "json_schema");
        format.set("json_schema", jsonSchema);
        body.set("response_format", format);
        body.put("mode", "enhanced"); // 중첩·병합 셀이 있는 복잡한 표용. 요금표가 정확히 이 대상이다

        String content;
        try {
            content = om.readTree(post(body.toString())).path("choices").path(0).path("message").path("content").asText(null);
        } catch (IOException e) {
            log.error("Upstage 응답 해석 실패", e);
            throw new ExtractException("문서를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (content == null || content.isBlank()) throw new ExtractException("문서에서 요금표를 찾지 못했습니다.");
        try {
            return om.readTree(content);
        } catch (IOException e) {
            log.error("Upstage 추출 결과가 JSON 이 아님: {}", content.substring(0, Math.min(500, content.length())));
            throw new ExtractException("문서에서 요금표를 찾지 못했습니다.");
        }
    }

    private String post(String body) {
        for (int attempt = 0; ; attempt++) {
            Response res = http.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .exchange((req, r) -> new Response(r.getStatusCode().value(), new String(r.getBody().readAllBytes())));
            if (res.status == 429 && attempt < RETRY_WAITS_MS.length) {
                log.warn("Upstage rate limit (429). {}ms 뒤 재시도", RETRY_WAITS_MS[attempt]);
                sleep(RETRY_WAITS_MS[attempt]);
                continue;
            }
            if (res.status >= 200 && res.status < 300) return res.body;
            // 원문 에러(키·쿼터 등 내부 정보가 섞일 수 있다)는 서버 로그에만 남긴다
            log.error("Upstage Universal Extraction 호출 실패 ({}): {}", res.status, res.body);
            // 413·415·422 는 파일 쪽 문제라 다시 눌러도 결과가 같다 — 무엇을 고쳐야 하는지 알려준다
            if (res.status == 413 || res.status == 415 || res.status == 422) {
                throw new ExtractException("문서를 읽을 수 없습니다. 50MB·100페이지 이하의 PDF·HWP·오피스 파일인지 확인해 주세요.");
            }
            throw new ExtractException("문서를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private record Response(int status, String body) {}

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractException("문서를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
