package com.crosshub.backoffice.product.inspect;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 검수에서 걸린 것 하나. 사람이 읽을 문장은 넣지 않는다 — 화면이 code 로 문구를 찾는다.
 * severity: low | medium | high. source: rule(계산으로 확정) | ai(확률적 판단). field: 문제 위치(ProductInput 기준 경로).
 * evidence: 왜 그렇게 봤는지(근거 값).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Finding(String code, String severity, String source, String field, Map<String, Object> evidence) {

    public static Finding rule(String code, String severity, String field, Map<String, Object> evidence) {
        return new Finding(code, severity, "rule", field, evidence);
    }

    static int rank(String severity) {
        return switch (severity) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }
}
