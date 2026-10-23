package com.crosshub.backoffice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 결제 설정 순수 로직: 수수료 하한(FE sharesExceedCommission), 이력 숫자 모양, 계좌 마스킹 */
class PaymentCommandServiceTest {

    @Test
    void exceeds_like_fe() {
        assertThat(PaymentCommandService.exceeds(null, 50, 50)).isFalse();      // 계약 전 업체는 검사 안 함
        assertThat(PaymentCommandService.exceeds(15, 3.25, 5)).isFalse();
        assertThat(PaymentCommandService.exceeds(8, 3.25, 5)).isTrue();
        assertThat(PaymentCommandService.exceeds(8, 3.0, 5)).isFalse();         // 같으면 통과 (크로스허브 몫 0)
    }

    @Test
    void jsNumber_drops_trailing_zero() {
        assertThat(PaymentCommandService.jsNumber(3.0)).isEqualTo(3L);
        assertThat(PaymentCommandService.jsNumber(3.25)).isEqualTo(3.25);
    }

    @Test
    void mask_last4() {
        assertThat(PaymentCommandService.mask(null)).isNull();
        assertThat(PaymentCommandService.mask("1234567890")).isEqualTo("****7890");
    }
}
