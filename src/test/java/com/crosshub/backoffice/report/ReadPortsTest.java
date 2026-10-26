package com.crosshub.backoffice.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import com.crosshub.backoffice.TestEntities;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.translation.ProductTexts;
import com.crosshub.backoffice.product.translation.ProductTranslation;
import com.crosshub.backoffice.product.translation.ProductTranslationReadService;
import com.crosshub.backoffice.product.translation.ProductTranslationRepository;
import com.crosshub.backoffice.product.translation.ProductTranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 조회를 BE 로 옮기면서 FE 계산을 그대로 포트한 것들 — 같은 답을 내는지 */
class ReadPortsTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 통계_비교기간은_걸친_달_수만큼_앞의_같은_날짜() {
        assertThat(StatsService.previousRange("2026-10-01", "2026-10-15")).isEqualTo(new StatsService.Range("2026-09-01", "2026-09-15"));
        assertThat(StatsService.previousRange("2026-05-01", "2026-10-15")).isEqualTo(new StatsService.Range("2025-11-01", "2026-04-15"));
        // 말일은 말일로, 그 달에 없는 날은 말일로
        assertThat(StatsService.dayMonthsBefore("2026-03-31", 1)).isEqualTo("2026-02-28");
        assertThat(StatsService.dayMonthsBefore("2026-04-30", 1)).isEqualTo("2026-03-31");
    }

    @Test
    void 번역_화면_항목은_섹션과_행이름을_같이_준다() {
        List<ProductTexts.Entry> e = ProductTexts.entries("이름", "", "{\"categories\":[{\"id\":\"c\",\"name\":\"성인\"}],\"plans\":[],\"roomTypes\":[]}",
                "[{\"id\":\"b1\",\"title\":\"일정표\",\"text\":\"내용\"}]", om);
        assertThat(e).extracting(ProductTexts.Entry::key).containsExactly("name", "pt.cat.c", "b.b1.title", "b.b1.text");
        assertThat(e).extracting(ProductTexts.Entry::section).containsExactly("basic", "price", 0, 0);
        assertThat(e).extracting(ProductTexts.Entry::field).containsExactly("name", "category", "blockTitle", "blockText");
    }

    @Test
    void 목록_번역상태는_빈칸이_있으면_none_전부_검수면_reviewed() {
        ProductTranslationReadService reads = new ProductTranslationReadService(mock(ProductRepository.class),
                mock(ProductTranslationRepository.class), mock(ProductTranslationService.class), om);
        Product p = TestEntities.product("p1", "pA", "live");
        p.setDescription("");
        p.setSourceLocale("ko");
        List<String> others = List.of("en", "ja", "zh-CN", "zh-TW", "vi");
        String sources = "{\"name\":\"상품 p1\"}";
        List<ProductTranslation> auto = others.stream().map(l -> ProductTranslation.of("p1", l, "{\"name\":\"x\"}", sources, false)).toList();
        List<ProductTranslation> reviewed = others.stream().map(l -> ProductTranslation.of("p1", l, "{\"name\":\"x\"}", sources, true)).toList();
        assertThat(reads.summary(p, auto)).isEqualTo("auto");
        assertThat(reads.summary(p, reviewed)).isEqualTo("reviewed");
        assertThat(reads.summary(p, auto.subList(0, 4))).isEqualTo("none");
        assertThat(reads.summary(TestEntities.product("p2", "pA", "draft"), auto)).isNull();
    }

    @Test
    void 로그는_업체계정이면_403_로그인내역은_크로스허브만() {
        ActivityLogService logs = new ActivityLogService(mock(NamedParameterJdbcTemplate.class), om);
        Caller partner = new Caller("u", "p", "partner", "pA", null, List.of("pA"));
        Caller affiliate = new Caller("u", "f", "affiliate", null, "aX", List.of("pA"));
        assertThat(((ResponseStatusException) catchThrowable(() -> logs.audit(partner, "2026-10-01", "2026-10-02", null, null, 1)))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(((ResponseStatusException) catchThrowable(() -> logs.logins(affiliate, "2026-10-01", "2026-10-02", null, null, "loginId", false, null, 1)))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(((ResponseStatusException) catchThrowable(() -> logs.access(affiliate, "2026-10-01", "2026-10-02", null, null, null, 1)))
                .getStatusCode().value()).isEqualTo(403);
    }
}
