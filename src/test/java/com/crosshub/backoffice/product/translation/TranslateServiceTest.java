package com.crosshub.backoffice.product.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.ai.SolarClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 여러 언어 한 번에: 원문은 한 번만 보내고 언어별 칸을 받는다. 묶음은 원문 1,500자 기준 */
class TranslateServiceTest {

    ObjectMapper om = new ObjectMapper();
    SolarClient solar = mock(SolarClient.class);

    /** 받은 원문 배열마다 {en: "en:원문", vi: "vi:원문"} 을 돌려주는 가짜 Solar */
    void echo() {
        when(solar.configured()).thenReturn(true);
        when(solar.call(anyString(), anyString(), eq("translations"), any())).thenAnswer(inv -> {
            JsonNode texts = om.readTree((String) inv.getArgument(1));
            ObjectNode out = om.createObjectNode();
            ArrayNode items = out.putArray("items");
            for (JsonNode t : texts) items.addObject().put("en", "en:" + t.asText()).put("vi", "vi:" + t.asText());
            return out;
        });
    }

    @Test
    void 짧은_글은_한_번에_모든_언어() {
        echo();
        Map<String, List<String>> out = new TranslateService(solar, om, false).translateAll(List.of("도쿄", "오사카"), List.of("en", "vi"), "ko");
        verify(solar, times(1)).call(anyString(), anyString(), eq("translations"), any());
        assertThat(out.get("en")).containsExactly("en:도쿄", "en:오사카");
        assertThat(out.get("vi")).containsExactly("vi:도쿄", "vi:오사카");
    }

    @Test
    void 긴_글은_원문_글자수로_나눈다() {
        echo();
        String big = "가".repeat(1000);
        Map<String, List<String>> out = new TranslateService(solar, om, false).translateAll(List.of(big, big, "끝"), List.of("en", "vi"), "ko");
        verify(solar, times(2)).call(anyString(), anyString(), eq("translations"), any());
        assertThat(out.get("en")).hasSize(3).last().isEqualTo("en:끝");
    }
}
