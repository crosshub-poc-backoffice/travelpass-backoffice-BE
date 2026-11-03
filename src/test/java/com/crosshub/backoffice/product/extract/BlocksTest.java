package com.crosshub.backoffice.product.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlocksTest {

    @Test
    void 제목_표_이미지_단위로_나눈다() {
        String doc = """
                ## 旅行代金
                | 日数 | 大人 |
                | 2日間 | 16,800円 |
                ※ 交付金により6,100円を割引しています。

                [image 1]
                1日目 博多港 → 郷ノ浦港

                ## お問い合わせ
                092-000-0000
                """;
        List<Blocks.Block> blocks = Blocks.split(doc);
        assertThat(blocks).extracting(Blocks.Block::text).containsExactly(
                "## 旅行代金\n| 日数 | 大人 |\n| 2日間 | 16,800円 |",
                "※ 交付金により6,100円を割引しています。",
                "[image 1]\n1日目 博多港 → 郷ノ浦港",
                "## お問い合わせ\n092-000-0000");
        assertThat(Blocks.render(blocks.subList(0, 1), Map.of("B1", "price"))).startsWith("<<B1 price>>\n## 旅行代金");
        assertThat(Blocks.snippet(blocks.get(2))).isEqualTo("1日目 博多港 → 郷ノ浦港");
    }
}
