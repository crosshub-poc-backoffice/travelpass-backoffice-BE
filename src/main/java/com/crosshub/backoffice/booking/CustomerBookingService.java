package com.crosshub.backoffice.booking;

import com.crosshub.backoffice.customer.CustomerPrincipal;
import com.crosshub.backoffice.customer.dto.CustomerDtos.BookingView;
import com.crosshub.backoffice.customer.dto.CustomerDtos.CreateBooking;
import com.crosshub.backoffice.payment.PgSetting;
import com.crosshub.backoffice.payment.PgSettingRepository;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 고객(idblock realm 토큰)의 "내 예약". 모든 조회·변경은 customerId(토큰 sub) 로 범위를 건다 — 남의 예약은 404.
 * 신청 규칙은 FE 의 옛 createBookingFromApp(TP 앱 API) 을 그대로 옮긴 것: 노출중 상품만, 수수료율 없는 업체 상품은 안 팖,
 * 금액 스냅샷, 즉시확정 상품은 정원 안이면 바로 confirmed + 결제 행, 견적형은 pending.
 */
@Service
public class CustomerBookingService {

    private final BookingRepository bookings;
    private final ProductRepository products;
    private final PaymentRepository payments;
    private final PgSettingRepository pgSettings;
    private final BookingService staff;
    private final ObjectMapper om;

    public CustomerBookingService(BookingRepository bookings, ProductRepository products, PaymentRepository payments,
                                  PgSettingRepository pgSettings, BookingService staff, ObjectMapper om) {
        this.bookings = bookings;
        this.products = products;
        this.payments = payments;
        this.pgSettings = pgSettings;
        this.staff = staff;
        this.om = om;
    }

    @Transactional(readOnly = true)
    public List<BookingView> list(CustomerPrincipal c) {
        return bookings.findByCustomerIdOrderByUseDateDescRequestedAtDesc(c.id()).stream().map(BookingView::from).toList();
    }

    @Transactional(readOnly = true)
    public BookingView get(CustomerPrincipal c, String id) {
        return BookingView.from(mine(c, id));
    }

    @Transactional
    public BookingView create(CustomerPrincipal c, CreateBooking req) {
        if (blank(req.productId()) || blank(req.nationality()) || blank(req.useDate()) || blank(req.idempotencyKey())
                || req.people() == null || req.amount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "required");
        }
        if (req.people() < 1 || req.amount() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid");
        LocalDate useDate;
        try {
            useDate = LocalDate.parse(req.useDate());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDate");
        }

        // 같은 멱등키로 이미 만들었으면 그걸 돌려준다 (다른 고객의 키와 겹치면 409)
        var existing = bookings.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            if (!c.id().equals(existing.get().getCustomerId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotencyKeyInUse");
            return BookingView.from(existing.get());
        }

        // 앱은 노출중 상품만 보여주니, 신청 시점에 숨겨졌거나 심사중이면 상품을 못 찾은 것과 같게 막는다
        Product p = products.findById(req.productId()).filter(x -> "live".equals(x.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "productNotFound"));
        // 수수료율이 비어 있는(계약 전) 업체 상품은 팔지 않는다
        if (p.getPartner() == null || p.getPartner().getCommissionRate() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "productNotFound");
        }
        int commissionRate = p.getPartner().getCommissionRate();
        Fees.Split split = Fees.splitAmount(req.amount(), commissionRate);
        int affiliateShareRate = p.getPartner().getProxyAffiliate() != null && p.getPartner().getProxyAffiliate().getShareRate() != null
                ? p.getPartner().getProxyAffiliate().getShareRate() : 0;
        String currency = p.getPartner().getCurrency();
        double pgFeeRate = currency == null ? 0 : pgSettings.findById(currency).map(PgSetting::getFeeRate).orElse(0.0);

        String status = "pending";
        if ("instant".equals(p.getConfirmType())) {
            // 즉시확정: 그 날 정원 안이면 바로 확정. 동시 요청끼리의 레이스는 막지 않는다 (PoC — FE 와 동일한 한계)
            SaleSchedule.Day day = SaleSchedule.resolve(p.getSchedule(), useDate, om);
            if (day != null) {
                if (!day.open()) throw new ResponseStatusException(HttpStatus.CONFLICT, "full");
                if (day.maxPeople() != null && bookings.sumPeopleOn(p.getId(), useDate) + req.people() > day.maxPeople()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "full");
                }
            }
            status = "confirmed";
        }

        Booking b = Booking.newFromCustomer(p.getId(), p.getPartnerId(), c.id(), c.name(), req.phone(), c.email(),
                req.nationality(), useDate, req.people(), req.amount(), commissionRate, split, affiliateShareRate, pgFeeRate,
                req.priceBreakdown() == null || req.priceBreakdown().isNull() ? null : req.priceBreakdown().toString(),
                p.getConfirmType(), status, req.idempotencyKey());
        try {
            bookings.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            // 위 조회 이후 거의 동시에 같은 키로 들어온 다른 요청이 먼저 만들었으면 그걸 돌려준다
            var winner = bookings.findByIdempotencyKey(req.idempotencyKey());
            if (winner.isPresent() && c.id().equals(winner.get().getCustomerId())) return BookingView.from(winner.get());
            throw e;
        }
        if ("confirmed".equals(status)) {
            // 즉시확정은 신청과 동시에 결제가 끝난 것이라 결제 행도 같이 남긴다. 견적형은 고객이 견적을 수락할 때 결제된다
            String pg = currency == null ? "-" : pgSettings.findById(currency).map(PgSetting::getPgName).orElse("-");
            payments.save(Payment.of(b.getId(), "payment", req.amount(), pg));
        }
        return BookingView.from(b, p.getName(), p.getPartner().getName(), currency);
    }

    /** 견적(quoted)에 수락/거절. 직원 화면의 "고객 대신 응답"과 같은 규칙(BookingService.applyCustomerDecision) */
    @Transactional
    public BookingView decide(CustomerPrincipal c, String id, String decision) {
        Booking b = mine(c, id);
        if (!"quoted".equals(b.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "notQuoted");
        if (!"accepted".equals(decision) && !"declined".equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDecision");
        }
        staff.applyCustomerDecision(b, decision);
        return BookingView.from(b);
    }

    /** 고객 직접 취소. 업체확인중(pending)은 결제 전이라 철회(withdrawn), 확정(confirmed)은 취소표대로 수수료를 떼고 canceled */
    @Transactional
    public BookingView cancel(CustomerPrincipal c, String id) {
        Booking b = mine(c, id);
        switch (b.getStatus()) {
            case "pending" -> b.setStatus("withdrawn");
            case "confirmed" -> {
                b.setStatus("canceled");
                b.setCancelFee(staff.cancelFeeNow(b));
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "notCancelable");
        }
        b.setUpdatedAt(OffsetDateTime.now());
        return BookingView.from(b);
    }

    private Booking mine(CustomerPrincipal c, String id) {
        return bookings.findByIdAndCustomerId(id, c.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다: " + id));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
