package com.crosshub.backoffice.customer;

import com.crosshub.backoffice.booking.CustomerBookingService;
import com.crosshub.backoffice.customer.dto.CustomerDtos.BookingView;
import com.crosshub.backoffice.customer.dto.CustomerDtos.CreateBooking;
import com.crosshub.backoffice.customer.dto.CustomerDtos.CreateInquiry;
import com.crosshub.backoffice.customer.dto.CustomerDtos.Decision;
import com.crosshub.backoffice.customer.dto.CustomerDtos.InquiryView;
import com.crosshub.backoffice.customer.dto.CustomerDtos.ProductSummary;
import com.crosshub.backoffice.inquiry.CustomerInquiryService;
import com.crosshub.backoffice.product.ProductRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객용 API (TP 앱 / demo/tp-web). 고객 realm(idblock) 토큰만 통과한다 (SecurityConfig: /api/customer/** → ROLE_CUSTOMER).
 * 고객 예약은 로그인 필수 — 모든 예약·문의는 토큰 sub 에 연결되고, 조회는 "내 것"만.
 *
 *   GET  /api/customer/products                  노출중 상품 목록
 *   GET  /api/customer/bookings                  내 예약
 *   POST /api/customer/bookings                  예약 신청 (즉시확정 상품은 정원 안이면 바로 확정)
 *   GET  /api/customer/bookings/{id}
 *   POST /api/customer/bookings/{id}/decision    견적 수락/거절 {decision: accepted|declined}
 *   POST /api/customer/bookings/{id}/cancel      취소 (pending → withdrawn, confirmed → canceled + 취소 수수료)
 *   GET  /api/customer/inquiries                 내 문의
 *   POST /api/customer/inquiries                 문의 접수
 *   GET  /api/customer/inquiries/{id}
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerApiController {

    private final CustomerBookingService bookings;
    private final CustomerInquiryService inquiries;
    private final ProductRepository products;

    public CustomerApiController(CustomerBookingService bookings, CustomerInquiryService inquiries, ProductRepository products) {
        this.bookings = bookings;
        this.inquiries = inquiries;
        this.products = products;
    }

    @GetMapping("/products")
    @Transactional(readOnly = true)
    public List<ProductSummary> products() {
        return products.findByStatusOrderByNameAsc("live").stream().map(ProductSummary::from).toList();
    }

    @GetMapping("/bookings")
    public List<BookingView> bookings(@AuthenticationPrincipal Jwt jwt) {
        return bookings.list(CustomerPrincipal.from(jwt));
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView createBooking(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateBooking req) {
        return bookings.create(CustomerPrincipal.from(jwt), req);
    }

    @GetMapping("/bookings/{id}")
    public BookingView booking(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return bookings.get(CustomerPrincipal.from(jwt), id);
    }

    @PostMapping("/bookings/{id}/decision")
    public BookingView decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody Decision req) {
        return bookings.decide(CustomerPrincipal.from(jwt), id, req.decision());
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return bookings.cancel(CustomerPrincipal.from(jwt), id);
    }

    @GetMapping("/inquiries")
    public List<InquiryView> inquiries(@AuthenticationPrincipal Jwt jwt) {
        return inquiries.list(CustomerPrincipal.from(jwt));
    }

    @PostMapping("/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public InquiryView createInquiry(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateInquiry req) {
        return inquiries.create(CustomerPrincipal.from(jwt), req);
    }

    @GetMapping("/inquiries/{id}")
    public InquiryView inquiry(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return inquiries.get(CustomerPrincipal.from(jwt), id);
    }
}
