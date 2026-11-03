package com.crosshub.backoffice.product.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 표를 격자로 펴고, 금액이 놓인 칸의 행·열 이름으로 (플랜, 인원 구분) 결합을 확인한다 */
class TableGridTest {

    @Test
    void 병합_칸을_걸친_칸마다_채운다() {
        String html = """
                <table>
                <tr><th rowspan="2">日数</th><th colspan="2">旅行代金</th></tr>
                <tr><th>大人</th><th>小人</th></tr>
                <tr><td>2日間</td><td>16,800円</td><td>11,750円</td></tr>
                </table>
                """;
        List<List<String>> grid = TableGrid.fromHtml(html);
        assertThat(grid).containsExactly(
                List.of("日数", "旅行代金", "旅行代金"),
                List.of("日数", "大人", "小人"),
                List.of("2日間", "16,800円", "11,750円"));
        assertThat(TableGrid.toMarkdown(grid)).startsWith("| 日数 | 旅行代金 | 旅行代金 |\n| --- | --- | --- |");
    }

    @Test
    void 금액이_다른_행_열에_있으면_그_칸의_이름을_돌려준다() {
        List<TableGrid.Table> tables = TableGrid.findAll("""
                | 日数 | 大人 | 小人 |
                | --- | --- | --- |
                | 2日間 | 16,800円 | 11,750円 |
                | 3日間 | 22,900円 | 14,800円 |
                """);
        assertThat(TableGrid.bind(tables, "3日間", "大人", 22900).fit()).isEqualTo(TableGrid.Fit.MATCH);
        TableGrid.Binding wrong = TableGrid.bind(tables, "2日間", "大人", 22900);
        assertThat(wrong.fit()).isEqualTo(TableGrid.Fit.MISPLACED);
        assertThat(wrong.rowLabel()).isEqualTo("3日間");
        assertThat(wrong.columnLabel()).isEqualTo("大人");
        // 조성금(6,100円)처럼 표 밖의 금액
        assertThat(TableGrid.bind(tables, "3日間", "大人", 6100).fit()).isEqualTo(TableGrid.Fit.NOT_IN_TABLE);
    }

    @Test
    void 한_칸의_괄호_속_두번째_금액은_소인_요금으로_본다() {
        List<TableGrid.Table> tables = TableGrid.findAll("""
                | 日数 | 旅行代金 |
                | 2日間 | 16,800円（11,750円） |
                """);
        assertThat(TableGrid.bind(tables, "2日間", "小人", 11750).fit()).isEqualTo(TableGrid.Fit.MATCH);
    }
}
