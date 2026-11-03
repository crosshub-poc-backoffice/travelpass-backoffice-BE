package com.crosshub.backoffice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Upstage Solar(OpenAI 호환 Chat Completions) 공용 호출. FE lib/api/solar.server.ts 를 옮긴 것.
 * 번역·상품 검수 등 Solar 를 쓰는 모든 기능이 여기를 지난다. API 키는 BE 에만 둔다 (.env UPSTAGE_API_KEY).
 *
 * - 응답은 json_schema(strict) 로 받아 JsonNode 로 돌려준다.
 * - Solar 의 실제 호출 제한을 아직 모른다 → 한 번에 하나씩만 보낸다 (lock). 번역·검수가 동시에 돌아도 서로 밀치지 않는다.
 *   제한이 확인되면 app.upstage.min-interval-ms 만 조정하면 된다.
 * - 원문 에러(키·쿼터 등 내부 정보 포함 가능)는 서버 로그에만 남기고, 호출한 쪽에는 일반 메시지만 돌려준다.
 */
@Component
public class SolarClient {

    private static final Logger log = LoggerFactory.getLogger(SolarClient.class);
    static final String ENDPOINT = "https://api.upstage.ai/v1/chat/completions";
    static final String MODEL = "solar-pro4";

    private final String apiKey;
    private final long minIntervalMs;
    /** 답 길이 상한. 생략하면 서버 기본값이 적용돼 요금 행이 많은 문서에서 JSON 이 중간에 끊긴 적이 있다. solar-pro4 는 출력 128K 까지 */
    private final int maxTokens;
    private final ObjectMapper om;
    private final RestClient http;
    private final ReentrantLock lock = new ReentrantLock(true);
    private volatile long lastCallAt = 0;

    public SolarClient(@Value("${app.upstage.api-key}") String apiKey,
                       @Value("${app.upstage.min-interval-ms}") long minIntervalMs,
                       @Value("${app.upstage.max-tokens:32768}") int maxTokens,
                       ObjectMapper om) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.minIntervalMs = minIntervalMs;
        this.maxTokens = maxTokens;
        this.om = om;
        this.http = RestClient.builder().baseUrl(ENDPOINT).build();
    }

    /** 키가 있으면 true. 없으면 호출하는 쪽이 건너뛸지(검수) 가짜 값을 쓸지(dev 번역) 에러를 낼지 정한다 */
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** Solar 호출. 응답은 schema 모양의 JSON. 추론은 끈 채로(reasoning_effort 생략), temperature 0 */
    public JsonNode call(String system, String user, String name, JsonNode schema) {
        return call(List.of(message("system", system), message("user", user)), name, schema);
    }

    public static Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    /**
     * 대화 전체를 직접 넘기는 호출. few-shot 예시(user/assistant 쌍)나, 검증에 실패한 답을 assistant 로 되돌려 주고
     * 고칠 점을 user 로 붙이는 재요청에 쓴다. 공통 부분(system·문서)을 앞에, 바뀌는 부분을 뒤에 두면 앞부분이 캐시될 여지가 생긴다
     */
    public JsonNode call(List<Map<String, String>> messages, String name, JsonNode schema) {
        if (!configured()) throw new SolarException("UPSTAGE_API_KEY가 설정되지 않았습니다.");
        lock.lock();
        try {
            long wait = minIntervalMs - (System.currentTimeMillis() - lastCallAt);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SolarException("호출이 중단되었습니다.", e);
                }
            }
            try {
                return doCall(messages, name, schema);
            } finally {
                lastCallAt = System.currentTimeMillis();
            }
        } finally {
            lock.unlock();
        }
    }

    private JsonNode doCall(List<Map<String, String>> messages, String name, JsonNode schema) {
        ObjectNode body = om.createObjectNode();
        body.put("model", MODEL);
        body.put("temperature", 0);
        if (maxTokens > 0) body.put("max_tokens", maxTokens);
        body.set("messages", om.valueToTree(messages));
        ObjectNode jsonSchema = om.createObjectNode();
        jsonSchema.put("name", name);
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", schema);
        ObjectNode responseFormat = om.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.set("json_schema", jsonSchema);
        body.set("response_format", responseFormat);

        String raw;
        try {
            raw = http.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new RestClientResponseException("upstage " + res.getStatusCode(), res.getStatusCode(), "",
                                res.getHeaders(), res.getBody().readAllBytes(), null);
                    })
                    .body(String.class);
        } catch (RestClientResponseException e) {
            log.error("Upstage API 호출 실패 ({}): {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new SolarException("AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        } catch (RuntimeException e) {
            log.error("Upstage API 호출 실패", e);
            throw new SolarException("AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }

        String finish = "";
        int length = -1;
        try {
            JsonNode json = om.readTree(raw);
            JsonNode choice = json.path("choices").path(0);
            finish = choice.path("finish_reason").asText();
            String content = choice.path("message").path("content").isTextual() ? choice.path("message").path("content").asText() : null;
            length = content == null ? -1 : content.length();
            if (content == null || content.isBlank() || "length".equals(finish)) {
                log.error("Upstage 응답이 비었거나 잘림 (finish_reason: {}, 글자 수: {}, 출력 토큰: {})",
                        finish, length, json.path("usage").path("completion_tokens").asText("?"));
                throw new SolarException("AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            }
            return om.readTree(content);
        } catch (SolarException e) {
            throw e;
        } catch (Exception e) {
            // 스택 전체 대신 원인만: 끊긴 JSON 인지(finish_reason·길이) 보면 충분하다
            log.error("Upstage 응답 해석 실패 (finish_reason: {}, 글자 수: {}): {}", finish, length, e.getMessage());
            throw new SolarException("AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }
}
