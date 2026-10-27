package com.crosshub.backoffice.product;

import com.crosshub.backoffice.storage.StoredFiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 상품 사진이 들어가는 세 자리 — images, blocks[].images, priceTable.priceImages.
 * DB 에는 저장소 안 경로(key)만 두고 응답에서 URL 로 바꾼다 (storage/StoredFiles).
 *
 * 저장할 때 우리 저장소 사진이 아닌 값은 조용히 뺀다 (FE validate.ts imagesFor 가 하던 것과 같다).
 * 칸마다 최대 10장, 같은 사진은 한 번만.
 */
public final class ProductImages {

    private ProductImages() {}

    static final int MAX = 10;
    private static final ObjectMapper OM = new ObjectMapper();

    /** 화면이 보낸 사진 목록 → DB 에 넣을 key 목록 */
    public static String[] keys(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            String k = StoredFiles.key(v, StoredFiles.PRODUCT_IMAGES);
            if (k != null && out.size() < MAX) out.add(k);
        }
        return out.toArray(new String[0]);
    }

    /** 상품 입력(FE ProductInput) JSON 의 세 자리를 key 로 바꾼다. 변경요청 본문을 저장할 때 쓴다 */
    public static void toKeys(ObjectNode input) {
        each(input, ProductImages::keyOrNull);
    }

    /** 상품 입력 JSON 의 세 자리를 URL 로 바꾼 복사본 (변경요청 본문을 돌려줄 때) */
    public static JsonNode toUrls(JsonNode input) {
        if (input == null || !input.isObject()) return input;
        ObjectNode copy = input.deepCopy();
        each(copy, StoredFiles::url);
        return copy;
    }

    /** blocks·priceTable 컬럼(JSON 문자열) 하나를 저장용으로 — 안의 사진을 key 로 */
    public static JsonNode blocksToKeys(JsonNode blocks) {
        return mapBlocks(blocks == null ? null : blocks.deepCopy(), ProductImages::keyOrNull);
    }

    public static JsonNode priceTableToKeys(JsonNode priceTable) {
        return mapPriceTable(priceTable == null ? null : priceTable.deepCopy(), ProductImages::keyOrNull);
    }

    /** DB 의 blocks·priceTable JSON 문자열 → 사진을 URL 로 바꾼 JSON 문자열 (응답용) */
    public static String blocksWithUrls(String json) {
        return rewrite(json, node -> mapBlocks(node, StoredFiles::url));
    }

    public static String priceTableWithUrls(String json) {
        return rewrite(json, node -> mapPriceTable(node, StoredFiles::url));
    }

    private static String keyOrNull(String v) {
        return StoredFiles.key(v, StoredFiles.PRODUCT_IMAGES);
    }

    private static void each(ObjectNode input, Function<String, String> f) {
        mapList(input, "images", f);
        mapBlocks(input.get("blocks"), f);
        mapPriceTable(input.get("priceTable"), f);
    }

    private static JsonNode mapBlocks(JsonNode blocks, Function<String, String> f) {
        if (blocks != null && blocks.isArray()) for (JsonNode b : blocks) if (b instanceof ObjectNode o) mapList(o, "images", f);
        return blocks;
    }

    private static JsonNode mapPriceTable(JsonNode priceTable, Function<String, String> f) {
        if (priceTable instanceof ObjectNode o) mapList(o, "priceImages", f);
        return priceTable;
    }

    private static void mapList(ObjectNode parent, String field, Function<String, String> f) {
        if (!(parent.get(field) instanceof ArrayNode list)) return;
        ArrayNode out = OM.createArrayNode();
        list.forEach(v -> {
            String mapped = v.isTextual() ? f.apply(v.asText()) : null;
            if (mapped != null && out.size() < MAX && !contains(out, mapped)) out.add(mapped);
        });
        parent.set(field, out);
    }

    private static boolean contains(ArrayNode list, String v) {
        for (JsonNode n : list) if (Objects.equals(n.asText(), v)) return true;
        return false;
    }

    private static String rewrite(String json, Function<JsonNode, JsonNode> f) {
        if (json == null) return null;
        try {
            return f.apply(OM.readTree(json)).toString();
        } catch (JsonProcessingException e) {
            return json; // 깨진 값은 손대지 않고 그대로 (예전과 같은 응답)
        }
    }
}
