package com.crosshub.backoffice.booking;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.booking.dto.BookingDecisionRequest;
import com.crosshub.backoffice.booking.dto.BookingDetailResponse;
import com.crosshub.backoffice.booking.dto.BookingQuoteRequest;
import com.crosshub.backoffice.booking.dto.BookingRefundRequest;
import com.crosshub.backoffice.booking.dto.BookingStatusRequest;
import com.crosshub.backoffice.booking.dto.BookingSummaryResponse;
import com.crosshub.backoffice.common.PageResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 API.
 *  GET  /api/v1/bookings?status=&productId=&keyword=&page=   (partner는 본인 업체만)
 *  GET  /api/v1/bookings/{id}
 *  POST /api/v1/bookings/{id}/quote               { amount, message } 업체가 견적 발송 (업체확인중만)
 *  POST /api/v1/bookings/{id}/customer-decision   { decision } 견적 수락/거절 시뮬레이션 (고객확인중만)
 *  POST /api/v1/bookings/{id}/status              { status, reason? } 거절·이용완료·취소
 *  POST /api/v1/bookings/{id}/refund               { cancelFee } 환불 완료 처리 (취소 상태만)
 *  실패 응답: {code} (FE 에러 키와 같은 이름: invalidAmount·invalidDecision ...)
 */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService service;
    private final CallerService callers;

    public BookingController(BookingService service, CallerService callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping
    public PageResponse<BookingSummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page) {
        Caller caller = callers.current();
        return service.list(caller, status, productId, keyword, page);
    }

    @GetMapping("/{id}")
    public BookingDetailResponse get(@PathVariable String id) {
        Caller caller = callers.current();
        return service.get(caller, id);
    }

    @PostMapping("/{id}/quote")
    public BookingDetailResponse sendQuote(@PathVariable String id, @RequestBody BookingQuoteRequest req) {
        Caller caller = callers.current();
        return service.sendQuote(caller, id, req);
    }

    @PostMapping("/{id}/customer-decision")
    public BookingDetailResponse respondAsCustomer(@PathVariable String id, @RequestBody BookingDecisionRequest req) {
        Caller caller = callers.current();
        return service.respondAsCustomer(caller, id, req);
    }

    @PostMapping("/{id}/status")
    public BookingDetailResponse setStatus(@PathVariable String id, @RequestBody BookingStatusRequest req) {
        Caller caller = callers.current();
        return service.setStatus(caller, id, req);
    }

    @PostMapping("/{id}/refund")
    public BookingDetailResponse completeRefund(@PathVariable String id, @RequestBody BookingRefundRequest req) {
        Caller caller = callers.current();
        return service.completeRefund(caller, id, req);
    }

    @ExceptionHandler(BookingException.class)
    public ResponseEntity<Map<String, String>> onBookingError(BookingException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }
}
