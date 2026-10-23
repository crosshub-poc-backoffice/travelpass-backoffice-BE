package com.crosshub.backoffice.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 정산 계산 헬퍼 — FE lib/fees.ts rateLabel, String(number), parseInt, bankLabel과 같은 결과여야 한다 */
class SettlementServiceTest {

    @Test
    void rateLabel_matches_fe() {
        assertThat(SettlementService.rateLabel(List.of())).isNull();
        assertThat(SettlementService.rateLabel(List.of(15.0, 15.0))).isEqualTo("15");
        assertThat(SettlementService.rateLabel(List.of(15.0, 12.0, 13.0))).isEqualTo("12~15");
        assertThat(SettlementService.rateLabel(List.of(3.25))).isEqualTo("3.25");
        assertThat(SettlementService.rateLabel(List.of(3.5, 3.504))).isEqualTo("3.5");
        assertThat(SettlementService.rateLabel(List.of(0.0))).isEqualTo("0");
    }

    @Test
    void num_prints_like_js() {
        assertThat(SettlementService.num(15)).isEqualTo("15");
        assertThat(SettlementService.num(3.25)).isEqualTo("3.25");
        assertThat(SettlementService.num(0)).isEqualTo("0");
    }

    @Test
    void leadingInt_like_parseInt() {
        assertThat(SettlementService.leadingInt("15")).isEqualTo(15);
        assertThat(SettlementService.leadingInt("12~15")).isEqualTo(12);
        assertThat(SettlementService.leadingInt("-")).isEqualTo(0);
    }

    @Test
    void bankLabel_like_fe() {
        assertThat(SettlementService.bankLabel(null, null, "홍길동")).isNull();
        assertThat(SettlementService.bankLabel("MUFG", "1234", "홍길동")).isEqualTo("MUFG 1234 (홍길동)");
        assertThat(SettlementService.bankLabel("MUFG", null, null)).isEqualTo("MUFG");
    }

    @Test
    void isMonthEnded() {
        assertThat(SettlementService.isMonthEnded("2000-01")).isTrue();
        assertThat(SettlementService.isMonthEnded("2999-12")).isFalse();
    }
}
