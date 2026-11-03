package com.crosshub.backoffice.product.extract;

/** 패키지 밖(ai.DocumentParseClient)에서 표 HTML 을 격자 markdown 으로 바꿀 때 쓰는 입구. TableGrid 는 추출 패키지 안에 둔다 */
public final class TableGrids {

    private TableGrids() {}

    /** HTML 표 → 병합 칸을 펼친 markdown 파이프 표. 표가 아니면 빈 문자열 */
    public static String markdownFromHtml(String html) {
        return TableGrid.toMarkdown(TableGrid.fromHtml(html));
    }
}
