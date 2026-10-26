package com.crosshub.backoffice.product.inspect;

import com.crosshub.backoffice.product.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 검수 대상. current 가 null 이면 최초 심사, 있으면 변경요청(전/후 비교).
 * Snapshot 은 Product 의 저장 JSON 을 검사기가 보기 쉬운 모양으로 풀어 둔 것 (FE ProductInput 에 해당).
 */
public record InspectTarget(Snapshot current, Snapshot proposed) {

    public boolean isEdit() {
        return current != null;
    }

    public record CancelRule(int daysBefore, int percent) {}

    public record Snapshot(String partnerId, String category, String name, List<String> images, String description,
                           int deadlineDays, String deadlineTime, String confirmType, String scheduleJson,
                           List<CancelRule> cancelRules, JsonNode priceTable, List<JsonNode> blocks) {

        public static Snapshot of(Product p, ObjectMapper om) {
            return of(p.getPartnerId(), p.getCategory(), p.getName(), p.getImages() == null ? List.of() : List.of(p.getImages()),
                    p.getDescription(), p.getDeadlineDays(), p.getDeadlineTime(), p.getConfirmType(),
                    p.getSchedule(), p.getCancelRules(), p.getPriceTable(), p.getBlocks(), om);
        }

        public static Snapshot of(String partnerId, String category, String name, List<String> images, String description,
                                  int deadlineDays, String deadlineTime, String confirmType, String scheduleJson,
                                  String cancelRulesJson, String priceTableJson, String blocksJson, ObjectMapper om) {
            List<CancelRule> rules = new ArrayList<>();
            for (JsonNode r : parse(cancelRulesJson, om)) {
                if (r.path("daysBefore").isNumber() && r.path("percent").isNumber()) {
                    rules.add(new CancelRule(r.path("daysBefore").asInt(), r.path("percent").asInt()));
                }
            }
            List<JsonNode> blocks = new ArrayList<>();
            for (JsonNode b : parse(blocksJson, om)) blocks.add(b);
            JsonNode pt = parse(priceTableJson, om);
            return new Snapshot(partnerId, category, name == null ? "" : name, images, description == null ? "" : description,
                    deadlineDays, deadlineTime == null ? "" : deadlineTime, confirmType, scheduleJson, rules,
                    pt.isObject() ? pt : om.createObjectNode(), blocks);
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
}
