package com.crosshub.backoffice.product.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** OCR 노이즈 정리: 끊긴 줄 잇기(언어별 띄어쓰기), 장식 기호·중복 줄 제거, 표·짧은 줄은 그대로 */
class DocumentCleanerTest {

    @Test
    void 일본어는_띄우지_않고_잇는다() {
        String out = DocumentCleaner.clean("博多港からフェリー太古に乗って上五島へ向かい、\n島の教会や自然をゆっくり楽しめるツアーです。");
        assertThat(out).isEqualTo("博多港からフェリー太古に乗って上五島へ向かい、島の教会や自然をゆっくり楽しめるツアーです。");
    }

    @Test
    void 한국어는_띄워서_잇는다() {
        String out = DocumentCleaner.clean("부산항에서 출발하는 페리를 타고 오사카에\n도착하는 2박 3일 일정입니다.");
        assertThat(out).isEqualTo("부산항에서 출발하는 페리를 타고 오사카에 도착하는 2박 3일 일정입니다.");
    }

    @Test
    void 짧은_줄_제목_목록_표는_잇지_않는다() {
        String in = "集合 22:30受付\n出発 23:45\n# 料金表\n| 大人 | 48,000円 |\n| 小人 | 32,000円 |\n- 朝食付き";
        assertThat(DocumentCleaner.clean(in)).isEqualTo(in);
    }

    @Test
    void 장식_기호와_중복_줄을_지운다() {
        String in = "■■■■■■\n★ ツアーのポイント\nお問い合わせは旅の輪九州まで\n━━━━━━━━\nお問い合わせは旅の輪九州まで\n\n\n\n大人";
        assertThat(DocumentCleaner.clean(in)).isEqualTo("ツアーのポイント\nお問い合わせは旅の輪九州まで\n\n大人");
    }

    @Test
    void 전각을_반각으로() {
        assertThat(DocumentCleaner.clean("４８，０００円")).isEqualTo("48,000円");
    }

    @Test
    void 표의_점선_리더는_화살표로() {
        assertThat(DocumentCleaner.clean("最終日: ホテル ······ 出発までフリータイム ‥‥‥ 福江港"))
                .isEqualTo("最終日: ホテル → 出発までフリータイム → 福江港");
        assertThat(DocumentCleaner.clean("1・2日目 博多港")).isEqualTo("1・2日目 博多港");
    }
}
