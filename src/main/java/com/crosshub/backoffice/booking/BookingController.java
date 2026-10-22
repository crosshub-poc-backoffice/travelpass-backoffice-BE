package com.crosshub.backoffice.booking;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.booking.dto.BookingDetailResponse;
import com.crosshub.backoffice.booking.dto.BookingSummaryResponse;
import com.crosshub.backoffice.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 조회 API.
 *  GET /api/v1/bookings?status=&productId=&keyword=&page=   (partner는 본인 업체만)
 *  GET /api/v1/bookings/{id}
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
}
