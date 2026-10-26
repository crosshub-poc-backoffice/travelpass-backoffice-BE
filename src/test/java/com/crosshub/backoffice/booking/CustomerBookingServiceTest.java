package com.crosshub.backoffice.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.customer.CustomerPrincipal;
import com.crosshub.backoffice.customer.dto.CustomerDtos.BookingView;
import com.crosshub.backoffice.customer.dto.CustomerDtos.CreateBooking;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.payment.PgSettingRepository;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/** 고객 예약 신청: 즉시확정+정원, 견적형 pending, 금액 스냅샷, 멱등키 */
class CustomerBookingServiceTest {

    BookingRepository bookings = mock(BookingRepository.class);
    ProductRepository products = mock(ProductRepository.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    PgSettingRepository pgSettings = mock(PgSettingRepository.class);
    BookingService staff = mock(BookingService.class);
    CustomerBookingService service = new CustomerBookingService(bookings, products, payments, pgSettings, staff, new ObjectMapper());

    CustomerPrincipal cust = new CustomerPrincipal("sub-1", "테스트 고객", "c@example.com");
    Product product = mock(Product.class);
    Partner partner = mock(Partner.class);

    // 월~금, 정원 2
    static final String SCHEDULE = "{\"base\":{\"start\":\"2026-11-02\",\"end\":\"2026-11-30\",\"weekdays\":[1,2,3,4,5],\"times\":[],\"slotPeople\":{},\"minPeople\":1,\"maxPeople\":2},\"exceptions\":[]}";

    @BeforeEach
    void setUp() {
        when(product.getId()).thenReturn("p1");
        when(product.getName()).thenReturn("온천");
        when(product.getStatus()).thenReturn("live");
        when(product.getPartnerId()).thenReturn("pt1");
        when(product.getPartner()).thenReturn(partner);
        when(product.getSchedule()).thenReturn(SCHEDULE);
        when(partner.getCommissionRate()).thenReturn(10);
        when(partner.getCurrency()).thenReturn("JPY");
        when(partner.getName()).thenReturn("업체");
        when(products.findById("p1")).thenReturn(Optional.of(product));
        when(bookings.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(pgSettings.findById("JPY")).thenReturn(Optional.empty());
    }

    private CreateBooking req(int people) {
        return new CreateBooking("p1", "KR", "2026-11-03", people, 10000, null, null, "key-1");
    }

    @Test
    void 즉시확정_정원_안이면_confirmed_결제행_금액스냅샷() {
        when(product.getConfirmType()).thenReturn("instant");
        when(bookings.sumPeopleOn("p1", LocalDate.of(2026, 11, 3))).thenReturn(0L);

        BookingView v = service.create(cust, req(2));

        assertThat(v.status()).isEqualTo("confirmed");
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).saveAndFlush(saved.capture());
        Booking b = saved.getValue();
        assertThat(b.getCustomerId()).isEqualTo("sub-1");
        assertThat(b.getCommissionRate()).isEqualTo(10);
        assertThat(b.getCommission()).isEqualTo(1000);
        assertThat(b.getNet()).isEqualTo(9000);
        assertThat(b.getIdempotencyKey()).isEqualTo("key-1");
        verify(payments).save(any(Payment.class));
    }

    @Test
    void 즉시확정_정원_넘으면_409_full() {
        when(product.getConfirmType()).thenReturn("instant");
        when(bookings.sumPeopleOn("p1", LocalDate.of(2026, 11, 3))).thenReturn(1L);
        assertThatThrownBy(() -> service.create(cust, req(2)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("full");
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void 견적형은_pending_결제_없음() {
        when(product.getConfirmType()).thenReturn("quote");
        BookingView v = service.create(cust, req(1));
        assertThat(v.status()).isEqualTo("pending");
        verify(payments, never()).save(any());
    }

    @Test
    void 같은_멱등키면_기존_예약_반환() {
        Booking existing = Booking.newFromCustomer("p1", "pt1", "sub-1", "고객", null, null, "KR",
                LocalDate.of(2026, 11, 3), 1, 10000, 10, Fees.splitAmount(10000, 10), 0, 0, null, "quote", "pending", "key-1");
        when(bookings.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
        BookingView v = service.create(cust, req(1));
        assertThat(v.id()).isEqualTo(existing.getId());
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void 숨긴_상품은_404_productNotFound() {
        when(product.getStatus()).thenReturn("hidden");
        assertThatThrownBy(() -> service.create(cust, req(1)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("productNotFound");
    }
}
