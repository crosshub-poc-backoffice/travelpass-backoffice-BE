package com.crosshub.backoffice.product.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.ai.DocumentParseClient;
import com.crosshub.backoffice.ai.SolarClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Parse → 정리 → Solar → 검산 → 재요청(최대 2) → 버리기 → 평면 모양. Upstage 호출은 mock */
class ProductExtractServiceTest {

    ObjectMapper om = new ObjectMapper();
    DocumentParseClient parser = mock(DocumentParseClient.class);
    SolarClient solar = mock(SolarClient.class);

    static final String DOC = """
            上五島の島旅 3〜5日間
            販売期間 2026/4/1〜2027/2/28
            | 出発日 | 大人 | 小人 |
            | 4月 | 48,000円 | 32,000円 |
            """;

    String answer(double adult) {
        return """
                {"sourceLocale":"ja","productName":"上五島の島旅 3〜5日間",
                 "description":{"highlights":["博多港からフェリーで上五島へ","教会めぐりと島の自然","3〜5日間から選べる日程"],
                   "summary":"上五島をゆっくりめぐる島旅です。"},
                 "priceRows":[
                   {"block":"B1","evidence":"| 4月 | 48,000円 | 32,000円 |","plan":"4月","category":"大人","amountText":"48,000円","amount":%s,"currency":"JPY"},
                   {"block":"B1","evidence":"| 4月 | 48,000円 | 32,000円 |","plan":"4月","category":"小人","amountText":"32,000円","amount":32000,"currency":"JPY"}],
                 "priceNote":"","salePeriod":{"block":"B1","evidence":"販売期間 2026/4/1〜2027/2/28","start":"2026-04-01","end":"2027-02-28"},
                 "sections":[],"cancelRules":[]}
                """.formatted(adult);
    }

    ProductExtractService service() throws Exception {
        when(parser.configured()).thenReturn(true);
        when(solar.configured()).thenReturn(true);
        when(parser.parse(any(), any())).thenReturn(DOC);
        when(solar.call(anyList(), eq("block_labels"), any())).thenReturn(om.readTree("{\"blocks\":[{\"id\":\"B1\",\"label\":\"price\"}]}"));
        return new ProductExtractService(parser, solar, om, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 틀린_값은_고칠_점을_붙여_다시_묻는다() throws Exception {
        ProductExtractService s = service();
        when(solar.call(anyList(), eq("product_draft"), any())).thenReturn(om.readTree(answer(4800)), om.readTree(answer(48000)));

        JsonNode flat = s.extract(new byte[] {1}, "a.pdf");

        ArgumentCaptor<List<Map<String, String>>> sent = ArgumentCaptor.forClass(List.class);
        verify(solar, times(2)).call(sent.capture(), eq("product_draft"), any());
        // mock 은 같은 리스트 객체를 잡으므로 두 번째 호출 때의 모습: system, 문서, 실패한 답, 고칠 점
        List<Map<String, String>> second = new ArrayList<>(sent.getAllValues().get(1));
        assertThat(second).extracting(m -> m.get("role")).containsExactly("system", "user", "assistant", "user");
        assertThat(second.get(3).get("content")).contains("48000");

        assertThat(flat.path("priceRows").get(0).path("yen").asLong()).isEqualTo(48000);
        assertThat(flat.path("saleStart").asText()).isEqualTo("2026-04-01");
        assertThat(flat.path("sourceLocale").asText()).isEqualTo("ja");
        assertThat(flat.path("description").asText())
                .isEqualTo("上五島をゆっくりめぐる島旅です。\n\n• 博多港からフェリーで上五島へ\n• 教会めぐりと島の自然\n• 3〜5日間から選べる日程");
        assertThat(flat.has("evidence")).isFalse();
        assertThat(flat.path("priceRows").get(0).has("evidence")).isFalse();
    }

    @Test
    void 두_번_고쳐도_틀리면_그_칸만_비운다() throws Exception {
        ProductExtractService s = service();
        when(solar.call(anyList(), eq("product_draft"), any())).thenReturn(om.readTree(answer(4800)));

        JsonNode flat = s.extract(new byte[] {1}, "a.pdf");

        verify(solar, times(1 + ProductExtractService.MAX_REPAIRS)).call(anyList(), eq("product_draft"), any());
        assertThat(flat.path("priceRows")).hasSize(1);
        assertThat(flat.path("priceRows").get(0).path("category").asText()).isEqualTo("小人");
        assertThat(flat.path("productName").asText()).isEqualTo("上五島の島旅 3〜5日間");
    }

    @Test
    void 문서에_소인이_있는데_요금표에_소인_열이_없으면_알린다() throws Exception {
        JsonNode adultOnly = om.readTree("""
                {"priceRows":[{"plan":"3日間","category":"旅行代金","amount":22800}]}""");
        String doc = "基本旅行代金大人／（　　）内は小人（6～11 歳）料金";
        assertThat(ProductExtractService.missingChildPrice(adultOnly, doc)).isTrue();
        JsonNode withChild = om.readTree("""
                {"priceRows":[{"plan":"3日間","category":"大人","amount":22800},{"plan":"3日間","category":"小人","amount":12000}]}""");
        assertThat(ProductExtractService.missingChildPrice(withChild, doc)).isFalse();
        assertThat(ProductExtractService.missingChildPrice(adultOnly, "大人 22,800円")).isFalse();
        assertThat(ProductExtractService.missingChildPrice(om.readTree("{\"priceRows\":[]}"), doc)).isFalse();
    }

    @Test
    void 일정은_코드가_줄로_잇고_할인_근거는_참고사항에_붙인다() throws Exception {
        JsonNode d = om.readTree("""
                {"priceNote":"燃油サーチャージ 3,000円",
                 "adjustments":[{"evidence":"大人お一人様6,100円を割引しています。","kind":"discount","label":"交付金","category":"大人","amountText":"6,100円","amount":6100},
                                {"evidence":"燃油サーチャージ 3,000円","kind":"surcharge","label":"燃油","category":"","amountText":"3,000円","amount":3000}],
                 "itinerary":[{"evidence":"","day":"1日目","text":"博多港 → 郷ノ浦港"},{"evidence":"","day":"","text":"フリータイム"}]}""");
        assertThat(ProductExtractService.priceNote(d)).isEqualTo("燃油サーチャージ 3,000円\n大人お一人様6,100円を割引しています。");
        assertThat(ProductExtractService.itinerary(d.path("itinerary"))).isEqualTo("1日目: 博多港 → 郷ノ浦港\nフリータイム");
    }

    @Test
    void 블록마다_라벨을_붙이고_무관한_블록은_빼고_묻는다() throws Exception {
        String doc = DOC + "\n## お問い合わせ\n九州旅行センター 092-000-0000\n";
        when(parser.configured()).thenReturn(true);
        when(solar.configured()).thenReturn(true);
        when(parser.parse(any(), any())).thenReturn(doc);
        when(solar.call(anyList(), eq("block_labels"), any())).thenReturn(om.readTree(
                "{\"blocks\":[{\"id\":\"B1\",\"label\":\"price\"},{\"id\":\"B2\",\"label\":\"irrelevant\"}]}"));
        when(solar.call(anyList(), eq("product_draft"), any())).thenReturn(om.readTree(answer(48000)));
        ProductExtractService s = new ProductExtractService(parser, solar, om, false);

        JsonNode flat = s.extract(new byte[] {1}, "a.pdf");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> sent = ArgumentCaptor.forClass(List.class);
        verify(solar).call(sent.capture(), eq("product_draft"), any());
        String input = sent.getValue().get(1).get("content");
        assertThat(input).startsWith("<<B1 price>>").doesNotContain("お問い合わせ");
        assertThat(flat.path("uncoveredBlocks")).isEmpty();
    }

    @Test
    void 분류가_실패해도_추출은_계속한다() throws Exception {
        ProductExtractService s = service();
        when(solar.call(anyList(), eq("block_labels"), any())).thenThrow(new com.crosshub.backoffice.ai.SolarException("down"));
        when(solar.call(anyList(), eq("product_draft"), any())).thenReturn(om.readTree(answer(48000)));

        JsonNode flat = s.extract(new byte[] {1}, "a.pdf");

        assertThat(flat.path("priceRows")).hasSize(2);
    }

    @Test
    void 페이지_이미지_OCR_을_제자리에_끼운다() {
        String md = "## 料金\n[[IMG 1]]\n本文\n[[IMG 2]]\n";
        String out = ProductExtractService.assemble(md, Map.of(1, "| 2日間 | 16,800円 |"));
        assertThat(out).contains("[image 1]\n| 2日間 | 16,800円 |").doesNotContain("IMG").doesNotContain("[image 2]");
        // 정리 뒤에도 이미지 표시가 한 줄로 남아야 블록이 이미지 단위로 나뉜다
        String cleaned = DocumentCleaner.clean(ProductExtractService.assemble("料金のご案内\n[[IMG 1]]\n本文です。", Map.of(1, "1日目 博多港 → 郷ノ浦港")));
        assertThat(Blocks.split(cleaned)).extracting(Blocks.Block::text).contains("[image 1]\n1日目 博多港 → 郷ノ浦港");
    }

    @Test
    void 라벨이_빠진_블록은_내용_블록으로_본다() throws Exception {
        List<Blocks.Block> blocks = List.of(new Blocks.Block("B1", "a"), new Blocks.Block("B2", "b"));
        Map<String, String> labels = ProductExtractService.labels(blocks, om.readTree("{\"blocks\":[{\"id\":\"B1\",\"label\":\"price\"}]}"));
        assertThat(labels).containsEntry("B1", "price").containsEntry("B2", "notice");
    }

    @Test
    void 재요청이_실패하면_직전_답으로_진행한다() throws Exception {
        ProductExtractService s = service();
        when(solar.call(anyList(), eq("product_draft"), any()))
                .thenReturn(om.readTree(answer(4800)))
                .thenThrow(new com.crosshub.backoffice.ai.SolarException("too long"));

        JsonNode flat = s.extract(new byte[] {1}, "a.pdf");

        // 틀린 대인 칸은 버리고 맞는 소인 칸·판매 기간은 남는다
        assertThat(flat.path("priceRows")).hasSize(1);
        assertThat(flat.path("saleStart").asText()).isEqualTo("2026-04-01");
    }
}
