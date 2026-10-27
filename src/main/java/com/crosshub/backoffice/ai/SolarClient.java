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
    private final ObjectMapper om;
    private final RestClient http;
    private final ReentrantLock lock = new ReentrantLock(true);
    private volatile long lastCallAt = 0;

    public SolarClient(@Value("${app.upstage.api-key}") String apiKey,
                       @Value("${app.upstage.min-interval-ms}") long minIntervalMs,
                       ObjectMapper om) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.minIntervalMs = minIntervalMs;
        this.om = om;
        this.http = RestClient.builder().baseUrl(ENDPOINT).build();
    }

    /** 키가 있으면 true. 없으면 호출하는 쪽이 건너뛸지(검수) 가짜 값을 쓸지(dev 번역) 에러를 낼지 정한다 */
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** Solar 호출. 응답은 schema 모양의 JSON. 추론은 끈 채로(reasoning_effort 생략), temperature 0 */
    public JsonNode call(String system, String user, String name, JsonNode schema) {
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
                return doCall(system, user, name, schema);
            } finally {
                lastCallAt = System.currentTimeMillis();
            }
        } finally {
            lock.unlock();
        }
    }

    private JsonNode doCall(String system, String user, String name, JsonNode schema) {
        ObjectNode body = om.createObjectNode();
        body.put("model", MODEL);
        body.put("temperature", 0);
        body.set("messages", om.valueToTree(List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user))));
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

        try {
            JsonNode json = om.readTree(raw);
            JsonNode choice = json.path("choices").path(0);
            String content = choice.path("message").path("content").isTextual() ? choice.path("message").path("content").asText() : null;
            if (content == null || content.isBlank() || "length".equals(choice.path("finish_reason").asText())) {
                log.error("Upstage 응답이 비었거나 잘림 (finish_reason: {})", choice.path("finish_reason").asText());
                throw new SolarException("AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            }
            return om.readTree(content);
        } catch (SolarException e) {
            throw e;
        } catch (Exception e) {
            log.error("Upstage 응답 해석 실패", e);
            throw new SolarException("AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }
}
