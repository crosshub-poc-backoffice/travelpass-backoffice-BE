package com.crosshub.backoffice.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.UserAccountRepository;
import com.crosshub.backoffice.booking.Booking;
import com.crosshub.backoffice.booking.BookingRepository;
import com.crosshub.backoffice.booking.BookingService;
import com.crosshub.backoffice.booking.PaymentRepository;
import com.crosshub.backoffice.booking.dto.BookingDecisionRequest;
import com.crosshub.backoffice.booking.dto.BookingQuoteRequest;
import com.crosshub.backoffice.booking.dto.BookingRefundRequest;
import com.crosshub.backoffice.booking.dto.BookingStatusRequest;
import com.crosshub.backoffice.inquiry.Inquiry;
import com.crosshub.backoffice.inquiry.InquiryMessageRepository;
import com.crosshub.backoffice.inquiry.InquiryRepository;
import com.crosshub.backoffice.inquiry.InquiryService;
import com.crosshub.backoffice.inquiry.dto.PostAnswerRequest;
import com.crosshub.backoffice.partner.AffiliateRepository;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerCommandService;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.partner.dto.PartnerUpsertRequest;
import com.crosshub.backoffice.payment.PaymentCommandService;
import com.crosshub.backoffice.payment.PgSettingRepository;
import com.crosshub.backoffice.payment.dto.PaymentDtos.AffiliateShareRequest;
import com.crosshub.backoffice.payment.dto.PaymentDtos.BankRequest;
import com.crosshub.backoffice.payment.dto.PaymentDtos.PgSettingRequest;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.ProductService;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.CreateRequest;
import com.crosshub.backoffice.product.change.ChangeRequestService;
import com.crosshub.backoffice.product.change.ProductChangeRequestRepository;
import com.crosshub.backoffice.product.dto.ProductStatusRequest;
import com.crosshub.backoffice.product.dto.ProductUpsertRequest;
import com.crosshub.backoffice.settlement.AffiliateSettlementRepository;
import com.crosshub.backoffice.settlement.SettlementRepository;
import com.crosshub.backoffice.settlement.SettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 역할별 범위 차단 — BE로 옮긴 쓰기마다 "남의 업체 데이터 → 404, 크로스허브 전용 → 403"인지.
 * 업체 A 계정(partnerA), 업체 A를 대행하는 연맹(affiliateX), 업체 B 데이터(pB)로 확인한다.
 * 막힐 때는 DB에 아무것도 쓰지 않아야 한다(작업 이력·결제 행 포함).
 */
class RoleScopeTest {

    static final Caller PARTNER_A = new Caller("u-a", "partner-a", "partner", "pA", null, List.of("pA"));
    static final Caller AFFILIATE_X = new Caller("u-x", "fed-x", "affiliate", null, "affX", List.of("pA"));

    /** 서비스마다 예외 타입이 달라서(ResponseStatusException, XxxException.status()) HTTP 상태만 뽑는다 */
    static int statusOf(Throwable e) {
        assertThat(e).as("막혀야 하는데 통과함").isNotNull();
        if (e instanceof ResponseStatusException r) return r.getStatusCode().value();
        try {
            return ((HttpStatus) e.getClass().getMethod("status").invoke(e)).value();
        } catch (ReflectiveOperationException x) {
            throw new AssertionError("상태 없는 예외: " + e, e);
        }
    }

    // ── 예약 ──
    @Test
    void 예약_남의업체는_404_쓰기없음() {
        BookingRepository repo = mock(BookingRepository.class);
        PaymentRepository payments = mock(PaymentRepository.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        Booking b = mock(Booking.class);
        when(b.getPartnerId()).thenReturn("pB");
        when(b.getStatus()).thenReturn("pending");
        when(repo.findById("b1")).thenReturn(Optional.of(b));
        BookingService s = new BookingService(repo, payments, mock(PgSettingRepository.class), audit, new ObjectMapper());

        for (Caller c : List.of(PARTNER_A, AFFILIATE_X)) {
            assertThat(statusOf(catchThrowable(() -> s.get(c, "b1")))).isEqualTo(404);
            assertThat(statusOf(catchThrowable(() -> s.sendQuote(c, "b1", new BookingQuoteRequest(1000, null))))).isEqualTo(404);
            assertThat(statusOf(catchThrowable(() -> s.respondAsCustomer(c, "b1", new BookingDecisionRequest("accepted"))))).isEqualTo(404);
            assertThat(statusOf(catchThrowable(() -> s.setStatus(c, "b1", new BookingStatusRequest("canceled", null))))).isEqualTo(404);
            assertThat(statusOf(catchThrowable(() -> s.completeRefund(c, "b1", new BookingRefundRequest(0))))).isEqualTo(404);
        }
        verify(b, never()).setStatus(any());
        verify(payments, never()).save(any());
        verify(audit, never()).save(any());
    }

    // ── 문의 ──
    @Test
    void 문의답변_남의업체는_404() {
        InquiryRepository repo = mock(InquiryRepository.class);
        InquiryMessageRepository messages = mock(InquiryMessageRepository.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        Inquiry i = mock(Inquiry.class);
        Product p = mock(Product.class);
        when(p.getPartnerId()).thenReturn("pB");
        when(i.getProduct()).thenReturn(p);
        when(repo.findById("q1")).thenReturn(Optional.of(i));
        InquiryService s = new InquiryService(repo, messages, audit);

        assertThat(statusOf(catchThrowable(() -> s.postAnswer(PARTNER_A, "q1", new PostAnswerRequest("hi", null, "ko"))))).isEqualTo(404);
        assertThat(statusOf(catchThrowable(() -> s.postAnswer(AFFILIATE_X, "q1", new PostAnswerRequest("hi", null, "ko"))))).isEqualTo(404);
        verify(messages, never()).saveAndFlush(any());
        verify(audit, never()).save(any());
    }

    // ── 상품 ──
    @Test
    void 상품_남의업체는_404_승인은_크로스허브만() {
        ProductRepository repo = mock(ProductRepository.class);
        PartnerRepository partners = mock(PartnerRepository.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        Product other = mock(Product.class);
        when(other.getPartnerId()).thenReturn("pB");
        when(other.getStatus()).thenReturn("live");
        when(repo.findById("prodB")).thenReturn(Optional.of(other));
        Product mineInReview = mock(Product.class);
        when(mineInReview.getPartnerId()).thenReturn("pA");
        when(mineInReview.getStatus()).thenReturn("review");
        when(repo.findById("prodA")).thenReturn(Optional.of(mineInReview));
        ProductService s = new ProductService(repo, partners, audit);

        ProductUpsertRequest upsert = new ProductUpsertRequest("pB", "x", null, null, null, null, null, null, null,
                new ObjectMapper().createObjectNode(), null, null, null, null, null, null);
        for (Caller c : List.of(PARTNER_A, AFFILIATE_X)) {
            assertThat(statusOf(catchThrowable(() -> s.get(c, "prodB")))).isEqualTo(404);
            assertThat(statusOf(catchThrowable(() -> s.update(c, "prodB", upsert, false)))).isEqualTo(404);
            assertThat(statusOf(catchThrowable(() -> s.setStatus(c, "prodB", new ProductStatusRequest("hidden", null))))).isEqualTo(404);
            assertThat(statusOf(catchThrowable(() -> s.delete(c, "prodB")))).isEqualTo(404);
            // 자기 업체 상품이라도 심사중 → 노출(승인)은 크로스허브만
            assertThat(statusOf(catchThrowable(() -> s.setStatus(c, "prodA", new ProductStatusRequest("live", null))))).isEqualTo(403);
        }
        // 연맹이 대행 안 하는 업체(pB)로 등록 → 403
        assertThat(statusOf(catchThrowable(() -> s.create(AFFILIATE_X, upsert, false)))).isEqualTo(403);
        verify(repo, never()).delete(any());
        verify(audit, never()).save(any());
    }

    // ── 업체 ──
    @Test
    void 업체_등록은_업체계정불가_수정은_범위안만_활성토글은_크로스허브만() {
        PartnerRepository partners = mock(PartnerRepository.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        Partner pB = mock(Partner.class);
        when(pB.getId()).thenReturn("pB");
        when(partners.findById("pB")).thenReturn(Optional.of(pB));
        PartnerCommandService s = new PartnerCommandService(partners, mock(AffiliateRepository.class), mock(ProductRepository.class),
                mock(BookingRepository.class), mock(UserAccountRepository.class), mock(PgSettingRepository.class), audit, new ObjectMapper());
        PartnerUpsertRequest req = new PartnerUpsertRequest("새업체", null, null, "JPY", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThat(statusOf(catchThrowable(() -> s.create(PARTNER_A, req)))).isEqualTo(403);
        assertThat(statusOf(catchThrowable(() -> s.update(PARTNER_A, "pB", req)))).isEqualTo(404);
        assertThat(statusOf(catchThrowable(() -> s.update(AFFILIATE_X, "pB", req)))).isEqualTo(404);
        assertThat(statusOf(catchThrowable(() -> s.toggleActive(AFFILIATE_X, "pB", false)))).isEqualTo(403);
        assertThat(statusOf(catchThrowable(() -> s.toggleActive(PARTNER_A, "pA", false)))).isEqualTo(403);
        verify(partners, never()).saveAndFlush(any());
        verify(audit, never()).save(any());
    }

    // ── 정산 ──
    @Test
    void 정산_지급처리는_크로스허브만_조회는_범위밖이면_빈목록() {
        SettlementRepository settlements = mock(SettlementRepository.class);
        AffiliateSettlementRepository affSettlements = mock(AffiliateSettlementRepository.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        SettlementService s = new SettlementService(mock(PartnerRepository.class), mock(AffiliateRepository.class), bookings,
                mock(PgSettingRepository.class), settlements, affSettlements, audit, new ObjectMapper());

        for (Caller c : List.of(PARTNER_A, AFFILIATE_X)) {
            assertThat(statusOf(catchThrowable(() -> s.markPaid(c, "pA", "2026-09", "2026-10-15")))).isEqualTo(403);
            assertThat(statusOf(catchThrowable(() -> s.unmarkPaid(c, "pA", "2026-09")))).isEqualTo(403);
            assertThat(statusOf(catchThrowable(() -> s.markAffiliatePaid(c, "affX", "2026-09", "JPY", "2026-10-15")))).isEqualTo(403);
            assertThat(statusOf(catchThrowable(() -> s.unmarkAffiliatePaid(c, "affX", "2026-09", "JPY")))).isEqualTo(403);
            // 업체 전환 값을 남의 업체로 보내도 빈 목록 (DB 조회 자체를 안 한다)
            assertThat(s.listMonthly(c, "2026-09", "pB")).isEmpty();
        }
        assertThat(s.listAffiliates(PARTNER_A, "2026-09")).isEmpty();
        verify(bookings, never()).findForSettlement(any(), any(), any());
        verify(settlements, never()).saveAndFlush(any());
        verify(affSettlements, never()).saveAndFlush(any());
        verify(audit, never()).save(any());
    }

    // ── 결제 설정 ──
    @Test
    void 결제설정_PG와_연맹몫은_크로스허브만_내연맹은_연맹만() {
        PgSettingRepository pg = mock(PgSettingRepository.class);
        AffiliateRepository affiliates = mock(AffiliateRepository.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        PaymentCommandService s = new PaymentCommandService(pg, affiliates, mock(PartnerRepository.class),
                mock(PaymentRepository.class), audit, new ObjectMapper());

        for (Caller c : List.of(PARTNER_A, AFFILIATE_X)) {
            assertThat(statusOf(catchThrowable(() -> s.savePgSetting(c, "JPY", new PgSettingRequest("PG", 3.0, 15))))).isEqualTo(403);
            assertThat(statusOf(catchThrowable(() -> s.saveAffiliateShare(c, "affX", new AffiliateShareRequest(5, null, null, null))))).isEqualTo(403);
        }
        assertThat(statusOf(catchThrowable(() -> s.saveMyAffiliateBank(PARTNER_A, new BankRequest("b", "1", "h"))))).isEqualTo(403);
        assertThat(statusOf(catchThrowable(() -> s.getMyAffiliate(PARTNER_A)))).isEqualTo(404);
        verify(pg, never()).saveAndFlush(any());
        verify(affiliates, never()).saveAndFlush(any());
        verify(audit, never()).save(any());
    }

    // ── 변경요청 ──
    @Test
    void 변경요청_남의업체_상품은_404_승인반려는_크로스허브만() {
        ProductChangeRequestRepository requests = mock(ProductChangeRequestRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        Product other = mock(Product.class);
        when(other.getPartnerId()).thenReturn("pB");
        when(other.getStatus()).thenReturn("live");
        when(products.findById("prodB")).thenReturn(Optional.of(other));
        ChangeRequestService s = new ChangeRequestService(requests, products, new ObjectMapper());

        for (Caller c : List.of(PARTNER_A, AFFILIATE_X)) {
            assertThat(statusOf(catchThrowable(() -> s.create(c, "prodB", new CreateRequest("delete", null, null))))).isEqualTo(404);
            assertThat(statusOf(catchThrowable(() -> s.get(c, "r1")))).isEqualTo(403);
            assertThat(statusOf(catchThrowable(() -> s.approve(c, "r1")))).isEqualTo(403);
            assertThat(statusOf(catchThrowable(() -> s.reject(c, "r1", "no")))).isEqualTo(403);
        }
        verify(requests, never()).saveAndFlush(any());
        verify(requests, never()).save(any());
    }
}
