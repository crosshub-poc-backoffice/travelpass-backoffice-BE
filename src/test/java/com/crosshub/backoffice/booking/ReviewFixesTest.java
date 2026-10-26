package com.crosshub.backoffice.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.booking.dto.BookingRefundRequest;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.payment.PgSettingRepository;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 코드 리뷰(보안·차등 테스트)에서 나온 수정의 회귀 테스트 */
class ReviewFixesTest {

    static final Caller CROSSHUB = new Caller("u-c", "crosshub", "crosshub", null, null, List.of());

    @Test
    void 수수료_계산은_큰_금액에서도_넘치지_않는다() {
        // int 곱셈이면 21,474,837 × 100 에서 넘쳐 수수료가 음수가 됐다
        Fees.Split s = Fees.splitAmount(21_474_837, 100);
        assertThat(s.commission()).isEqualTo(21_474_837);
        assertThat(s.net()).isZero();

        Fees.Split s15 = Fees.splitAmount(200_000_000, 15);
        assertThat(s15.commission()).isEqualTo(30_000_000);
        assertThat(s15.commission() + s15.net()).isEqualTo(200_000_000);

        // 규칙이 없으면 100% — 큰 금액에서도 금액 그대로
        assertThat(Fees.cancelFeeFor(1_249_302_325, List.of(), 10)).isEqualTo(1_249_302_325);
    }

    @Test
    void 환불_수수료가_빠지면_0원_면제가_아니라_400() {
        BookingRepository repo = mock(BookingRepository.class);
        PaymentRepository payments = mock(PaymentRepository.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        Booking b = com.crosshub.backoffice.TestEntities.booking("b1", "p1", "canceled", 5000);
        when(repo.findByIdForUpdate("b1")).thenReturn(Optional.of(b));
        BookingService s = new BookingService(repo, payments, mock(PgSettingRepository.class), audit, new ObjectMapper());

        Throwable e = catchThrowable(() -> s.completeRefund(CROSSHUB, "b1", new BookingRefundRequest(null)));
        assertThat(e).isInstanceOf(BookingException.class);
        assertThat(((BookingException) e).status().value()).isEqualTo(400);
        assertThat(((BookingException) e).code()).isEqualTo("invalidAmount");
        assertThat(b.getStatus()).isEqualTo("canceled");
        assertThat(b.getCancelFee()).isEqualTo(5000);
        verify(payments, never()).save(any());
        verify(audit, never()).save(any());
    }

    @Test
    void 목록_status에_모르는_값이면_500이_아니라_400() {
        BookingService bookings = new BookingService(mock(BookingRepository.class), mock(PaymentRepository.class),
                mock(PgSettingRepository.class), mock(AuditLogRepository.class), new ObjectMapper());
        Throwable e1 = catchThrowable(() -> bookings.list(CROSSHUB, "foo", null, null, 1));
        assertThat(e1).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) e1).getStatusCode().value()).isEqualTo(400);

        ProductService products = new ProductService(mock(ProductRepository.class), mock(PartnerRepository.class), mock(AuditLogRepository.class));
        Throwable e2 = catchThrowable(() -> products.list(CROSSHUB, "foo"));
        assertThat(e2).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) e2).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void 예약_쓰기는_행_잠금_조회로_읽는다() {
        // 잠금 없이 읽으면 환불 더블클릭 두 요청이 모두 "취소됨"을 보고 환불 행이 2개 생긴다
        BookingRepository repo = mock(BookingRepository.class);
        when(repo.findByIdForUpdate("b1")).thenReturn(Optional.empty());
        BookingService s = new BookingService(repo, mock(PaymentRepository.class), mock(PgSettingRepository.class),
                mock(AuditLogRepository.class), new ObjectMapper());
        catchThrowable(() -> s.completeRefund(CROSSHUB, "b1", new BookingRefundRequest(0)));
        catchThrowable(() -> s.respondAsCustomer(CROSSHUB, "b1", new com.crosshub.backoffice.booking.dto.BookingDecisionRequest("accepted")));
        verify(repo, org.mockito.Mockito.times(2)).findByIdForUpdate("b1");
        verify(repo, never()).findById(any());
    }

    // ── 예약 상태 규칙 (엔티티만, mock 없이) ──

    @Test
    void 견적은_업체확인중일때만_금액은_양수_메시지는_2000자() {
        Booking b = com.crosshub.backoffice.TestEntities.booking("b1", "p1", "pending", null);
        assertThat(catchThrowable(() -> b.sendQuote(0, null))).isInstanceOf(BookingException.class);
        assertThat(catchThrowable(() -> b.sendQuote(1000, "x".repeat(2001)))).isInstanceOf(BookingException.class);
        b.sendQuote(1000, "  ");
        assertThat(b.getStatus()).isEqualTo("quoted");
        assertThat(b.getQuoteMessage()).isNull();
        assertThat(b.getCustomerDecision()).isEqualTo("waiting");
        // 두 번째 견적은 상태가 바뀌어 404
        assertThat(catchThrowable(() -> b.sendQuote(2000, null))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 견적_수락은_견적금액으로_수수료를_다시_계산한다() {
        Booking b = com.crosshub.backoffice.TestEntities.booking("b1", "p1", "pending", null);
        org.springframework.test.util.ReflectionTestUtils.setField(b, "commissionRate", 15);
        b.sendQuote(200_000, null);
        assertThat(b.respondToQuote("accepted")).isTrue();
        assertThat(b.getStatus()).isEqualTo("confirmed");
        assertThat(b.getAmount()).isEqualTo(200_000);
        assertThat(b.getCommission()).isEqualTo(30_000);
        assertThat(b.getNet()).isEqualTo(170_000);
    }

    @Test
    void 확정_취소만_수수료가_붙고_업체확인중_거절은_0원() {
        Booking confirmed = com.crosshub.backoffice.TestEntities.booking("b1", "p1", "confirmed", null);
        confirmed.changeStatus("canceled", "고객 요청", () -> 7000);
        assertThat(confirmed.getCancelFee()).isEqualTo(7000);
        assertThat(confirmed.getCancelReason()).isEqualTo("고객 요청");

        Booking pending = com.crosshub.backoffice.TestEntities.booking("b2", "p1", "pending", null);
        pending.changeStatus("rejected", null, () -> { throw new AssertionError("거절엔 수수료 계산 안 함"); });
        assertThat(pending.getStatus()).isEqualTo("rejected");
        // 허용 안 된 전이(업체확인중 → 이용완료)는 404
        Booking other = com.crosshub.backoffice.TestEntities.booking("b3", "p1", "pending", null);
        assertThat(catchThrowable(() -> other.changeStatus("completed", null, () -> 0))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 환불은_수수료를_깎을수만_있고_환불액을_돌려준다() {
        Booking b = com.crosshub.backoffice.TestEntities.booking("b1", "p1", "canceled", 5000);
        assertThat(catchThrowable(() -> b.refund(6000, 100_000))).isInstanceOf(BookingException.class);
        assertThat(catchThrowable(() -> b.refund(-1, 100_000))).isInstanceOf(BookingException.class);
        assertThat(b.refund(3000, 100_000)).isEqualTo(97_000);
        assertThat(b.getStatus()).isEqualTo("refunded");
        assertThat(b.getCancelFee()).isEqualTo(3000);
        // 두 번째 환불은 상태가 바뀌어 404
        assertThat(catchThrowable(() -> b.refund(0, 100_000))).isInstanceOf(ResponseStatusException.class);
    }
}
