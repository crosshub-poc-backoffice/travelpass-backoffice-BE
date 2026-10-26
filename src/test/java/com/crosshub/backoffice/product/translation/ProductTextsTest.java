package com.crosshub.backoffice.product.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 번역 키 추출·빈칸/원문변경 판정·기본 제목 표 — FE lib/products/translation.ts 와 같은 답을 내는지 */
class ProductTextsTest {

    ObjectMapper om = new ObjectMapper();

    static final String PRICE_TABLE = """
        {"categories":[{"id":"c1","name":"성인"},{"id":"c2","name":"아동"}],"plans":[{"id":"p1","name":"3일권","prices":{"c1":1000,"c2":500}}],
         "priceImages":[],"priceText":"","title":"요금 안내","roomTypes":[],"roomPricing":{"base":{},"exceptions":[]}}
        """;
    static final String BLOCKS = """
        [{"id":"b1","title":"일정표","images":[],"text":"1일차 도착","priceGroups":[{"id":"g1","title":"","required":false,
          "columns":[{"id":"col1","name":"금액"}],"options":[{"id":"o1","name":"스위트 업그레이드","amounts":{"col1":{"sign":"add","price":100}}}]}],
          "choiceColumns":[{"id":"cc1","name":"시간"}],"choices":[{"id":"ch1","name":"오전","values":{"cc1":"09:00"}}],"choicesRequired":false}]
        """;

    @Test
    void 키는_id로_만들고_빈_칸은_뺀다() {
        Map<String, String> m = ProductTexts.sourceMap("도쿄 투어", "", PRICE_TABLE, BLOCKS, om);
        assertThat(m).containsEntry("name", "도쿄 투어")
                .doesNotContainKey("description")
                .containsEntry("pt.title", "요금 안내")
                .containsEntry("pt.cat.c1", "성인").containsEntry("pt.cat.c2", "아동")
                .containsEntry("pt.plan.p1", "3일권")
                .doesNotContainKey("pt.text")
                .containsEntry("b.b1.title", "일정표").containsEntry("b.b1.text", "1일차 도착")
                .doesNotContainKey("b.b1.g.g1.title")
                .containsEntry("b.b1.g.g1.col.col1", "금액")
                .containsEntry("b.b1.g.g1.opt.o1", "스위트 업그레이드")
                .containsEntry("b.b1.ccol.cc1", "시간")
                .containsEntry("b.b1.ch.ch1", "오전")
                .containsEntry("b.b1.ch.ch1.v.cc1", "09:00");
        assertThat(m.keySet()).first().isEqualTo("name"); // 순서 유지 (번역 화면 행 순서)
    }

    @Test
    void 숙박은_기준플랜_대신_객실타입() {
        String lodging = """
            {"categories":[],"plans":[],"priceImages":[],"priceText":"조식 포함","title":"","roomTypes":[{"id":"r1","name":"스위트"}],
             "roomPricing":{"base":{"r1":{"price":30000,"count":2}},"exceptions":[]}}
            """;
        Map<String, String> m = ProductTexts.sourceMap("료칸", "", lodging, "[]", om);
        assertThat(m).containsEntry("pt.room.r1", "스위트").containsEntry("pt.text", "조식 포함");
        assertThat(m.keySet()).noneMatch(k -> k.startsWith("pt.cat.") || k.startsWith("pt.plan."));
    }

    @Test
    void 빈_칸은_missing_원문이_바뀐_칸은_stale() {
        Map<String, String> current = Map.of("name", "새 이름", "description", "소개");
        Map<String, String> sources = Map.of("name", "옛 이름", "description", "소개");
        Map<String, String> texts = Map.of("name", "Old name", "description", "");
        ProductTexts.Outdated o = ProductTexts.outdated(current, sources, texts);
        assertThat(o.missing()).containsExactly("description");
        assertThat(o.stale()).containsExactly("name");
    }

    @Test
    void 기본_제목은_어느_언어든_알아보고_표로_바꾼다() {
        assertThat(DefaultTitles.translate("行程表", "ko")).isEqualTo("일정표");
        assertThat(DefaultTitles.translate("금액", "en")).isEqualTo("Price");
        assertThat(DefaultTitles.translate("우리만의 제목", "en")).isNull();
    }

    @Test
    void 번역_대상은_원문_언어를_뺀_5개() {
        assertThat(Locales.targets("ko")).containsExactly("en", "ja", "zh-CN", "zh-TW", "vi");
        assertThat(Locales.targets(null)).hasSize(6);
        assertThat(Locales.normalize("zh")).isEqualTo("zh-CN");
        assertThat(List.of("ko", "zh-TW")).allMatch(Locales::isLocale);
        assertThat(Locales.isLocale("fr")).isFalse();
    }
}
