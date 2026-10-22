package com.crosshub.backoffice.booking;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.booking.dto.BookingDetailResponse;
import com.crosshub.backoffice.booking.dto.BookingSummaryResponse;
import com.crosshub.backoffice.common.PageResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookingService {

    private static final int PAGE_SIZE = 20;

    /** 진행 중(묶음) 상태 — v1의 ACTIVE_BOOKING_STATUSES와 동일하게 맞춘다. */
    private static final List<String> ACTIVE_STATUSES = List.of("pending", "quoted", "confirmed");

    private final BookingRepository repository;

    public BookingService(BookingRepository repository) {
        this.repository = repository;
    }

    /**
     * 목록. partner는 자기 업체만, crosshub는 전체.
     * status="active"면 진행 중 묶음, 특정 상태면 그 상태, null이면 전체.
     * 정렬: 진행 중은 이용일 빠른 순, 그 외는 이용일 최근 순 (동일하면 접수 빠른 순).
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingSummaryResponse> list(
            Caller caller, String status, String productId, String keyword, int page) {

        boolean allPartners = caller.isCrosshub();
        List<String> partnerIds = allPartners ? List.of("_none_") : List.of(requirePartner(caller));

        List<String> statuses = "active".equals(status) ? ACTIVE_STATUSES
                : (status != null && !status.isBlank() ? List.of(status) : null);
        boolean anyStatus = statuses == null;
        // status는 Postgres enum이라 더미도 유효한 enum 값이어야 한다("pending"). anyStatus=true면 어차피 안 쓰임.
        List<String> statusParam = anyStatus ? List.of("pending") : statuses;

        boolean anyProduct = (productId == null || productId.isBlank());
        String productParam = anyProduct ? "_none_" : productId;

        boolean noKeyword = (keyword == null || keyword.isBlank());
        String kw = noKeyword ? "_none_" : "%" + keyword.trim().toLowerCase() + "%";

        // 주의: List.of(...).contains(null)은 NPE. status가 null일 수 있으니 null 가드.
        boolean upcomingFirst = "active".equals(status)
                || (status != null && ACTIVE_STATUSES.contains(status));
        Sort sort = Sort.by(
                new Sort.Order(upcomingFirst ? Sort.Direction.ASC : Sort.Direction.DESC, "useDate"),
                new Sort.Order(Sort.Direction.ASC, "requestedAt"));
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), PAGE_SIZE, sort);

        Page<Booking> result = repository.search(
                allPartners, partnerIds, anyStatus, statusParam,
                anyProduct, productParam, noKeyword, kw, pageable);
        List<BookingSummaryResponse> rows = result.getContent().stream()
                .map(BookingSummaryResponse::from)
                .toList();
        return PageResponse.of(result, rows);
    }

    /** 상세. 소유 업체만(아니면 404). */
    @Transactional(readOnly = true)
    public BookingDetailResponse get(Caller caller, String id) {
        Booking b = repository.findById(id)
                .orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(b.getPartnerId())) {
            throw notFound(id);
        }
        return BookingDetailResponse.from(b);
    }

    private static String requirePartner(Caller caller) {
        if (caller.partnerId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "업체 소속 계정이 아닙니다.");
        }
        return caller.partnerId();
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다: " + id);
    }
}
