package com.crosshub.backoffice.booking;

import com.crosshub.backoffice.account.AuditLog;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.booking.dto.BookingDecisionRequest;
import com.crosshub.backoffice.booking.dto.BookingDetailResponse;
import com.crosshub.backoffice.booking.dto.BookingQuoteRequest;
import com.crosshub.backoffice.booking.dto.BookingRefundRequest;
import com.crosshub.backoffice.booking.dto.BookingStatusRequest;
import com.crosshub.backoffice.booking.dto.BookingSummaryResponse;
import com.crosshub.backoffice.common.PageResponse;
import com.crosshub.backoffice.payment.PgSetting;
import com.crosshub.backoffice.payment.PgSettingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

    // 화면 버튼이 허용하는 전환만 통과시킨다 (bookings-view.tsx의 버튼 노출 조건과 1:1로 맞춘다)
    private static final Map<String, List<String>> ALLOWED_TRANSITIONS = Map.of(
            "pending", List.of("rejected"),
            "quoted", List.of(),
            "confirmed", List.of("completed", "canceled"),
            "rejected", List.of(),
            "completed", List.of(),
            "canceled", List.of(),
            "refunded", List.of(),
            "withdrawn", List.of());

    private final BookingRepository repository;
    private final PaymentRepository payments;
    private final PgSettingRepository pgSettings;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;

    public BookingService(
            BookingRepository repository,
            PaymentRepository payments,
            PgSettingRepository pgSettings,
            AuditLogRepository auditLogs,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.payments = payments;
        this.pgSettings = pgSettings;
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    /**
     * 목록. partner는 자기 업체, affiliate는 대행 업체, crosshub는 전체.
     * status="active"면 진행 중 묶음, 특정 상태면 그 상태, null이면 전체.
     * 정렬: 진행 중은 이용일 빠른 순, 그 외는 이용일 최근 순 (동일하면 접수 빠른 순).
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingSummaryResponse> list(
            Caller caller, String status, String productId, String keyword, int page) {

        boolean allPartners = caller.isCrosshub();
        List<String> partnerIds = allPartners ? List.of("_none_") : caller.partnerIdsForQuery();

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

    /** 업체가 고객 신청에 견적서를 보낸다. 업체확인중일 때만 */
    @Transactional
    public BookingDetailResponse sendQuote(Caller caller, String id, BookingQuoteRequest req) {
        Booking b = findScoped(caller, id);
        if (!"pending".equals(b.getStatus())) throw notFound(id);
        if (req.amount() <= 0) throw new BookingException(HttpStatus.BAD_REQUEST, "invalidAmount");

        b.setStatus("quoted");
        b.setQuoteAmount(req.amount());
        b.setQuoteMessage((req.message() == null || req.message().isBlank()) ? null : req.message());
        b.setCustomerDecision("waiting");
        b.setUpdatedAt(OffsetDateTime.now());
        return BookingDetailResponse.from(b);
    }

    /** 고객이 견적에 응답한다(관리자 화면에서 대신 눌러 시뮬레이션). 고객확인중일 때만 */
    @Transactional
    public BookingDetailResponse respondAsCustomer(Caller caller, String id, BookingDecisionRequest req) {
        Booking b = findScoped(caller, id);
        if (!"quoted".equals(b.getStatus())) throw notFound(id);
        String decision = req.decision();
        if (!"accepted".equals(decision) && !"declined".equals(decision)) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "invalidDecision");
        }

        if ("accepted".equals(decision)) {
            // 견적을 수락하면 그 금액으로 결제된다 — 판매가를 견적 금액으로 바꾸고 수수료·정산액을 다시 계산한 뒤 결제 행을 남긴다
            int amount = b.getQuoteAmount() != null ? b.getQuoteAmount() : b.getAmount();
            Fees.Split split = Fees.splitAmount(amount, b.getCommissionRate());
            b.setStatus("confirmed");
            b.setCustomerDecision("accepted");
            b.setAmount(amount);
            b.setCommission(split.commission());
            b.setNet(split.net());
            payments.save(Payment.of(id, "payment", amount, pgNameFor(b)));
        } else {
            // 고객이 견적을 거절 = 결제 전에 고객이 물러남 → 신청취소 (업체 거절과 구분)
            b.setStatus("withdrawn");
            b.setCustomerDecision("declined");
        }
        b.setUpdatedAt(OffsetDateTime.now());
        return BookingDetailResponse.from(b);
    }

    /** 견적 거절, 이용완료 처리, 예약 취소. reason은 거절·취소일 때만 쓴다(선택 입력) */
    @Transactional
    public BookingDetailResponse setStatus(Caller caller, String id, BookingStatusRequest req) {
        Booking b = findScoped(caller, id);
        List<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(b.getStatus(), List.of());
        if (!allowed.contains(req.status())) throw notFound(id);

        if ("canceled".equals(req.status())) {
            // 돈을 받은 예약(확정)만 취소 수수료가 붙는다. 업체확인중·고객확인중은 받은 돈이 없어 0
            int fee = "confirmed".equals(b.getStatus()) ? cancelFeeNow(b) : 0;
            b.setStatus("canceled");
            b.setCancelFee(fee);
            b.setCancelReason((req.reason() == null || req.reason().isBlank()) ? null : req.reason());
        } else if ("rejected".equals(req.status())) {
            b.setStatus("rejected");
            b.setCancelReason((req.reason() == null || req.reason().isBlank()) ? null : req.reason());
        } else {
            b.setStatus(req.status());
        }
        b.setUpdatedAt(OffsetDateTime.now());
        return BookingDetailResponse.from(b);
    }

    /**
     * 환불 완료 처리. 취소할 때 취소표로 계산해 둔 수수료를 업체가 확인하고, 필요하면 낮춰서(깎아주기만) 환불한다.
     * 수수료를 올리는 건 막는다 — 고객이 미리 본 취소표보다 더 떼면 분쟁이 된다. 처리 내역은 작업 이력에 남긴다.
     */
    @Transactional
    public BookingDetailResponse completeRefund(Caller caller, String id, BookingRefundRequest req) {
        Booking b = findScoped(caller, id);
        if (!"canceled".equals(b.getStatus())) throw notFound(id);

        int ruleFee = b.getCancelFee() != null ? b.getCancelFee() : 0;
        int cancelFee = req.cancelFee();
        if (cancelFee < 0 || cancelFee > ruleFee) throw new BookingException(HttpStatus.BAD_REQUEST, "invalidAmount");

        // 환불 행. 받은 돈에서 취소 수수료를 뺀 만큼. 받은 돈이 없으면(견적 전 취소) 환불 행도 없다
        int paid = payments.findByBookingId(id).stream()
                .mapToInt(p -> "payment".equals(p.getType()) ? p.getAmount() : -p.getAmount())
                .sum();
        int refundAmount = paid - cancelFee;

        b.setStatus("refunded");
        b.setCancelFee(cancelFee);
        b.setUpdatedAt(OffsetDateTime.now());

        if (refundAmount > 0) {
            payments.save(Payment.of(id, "refund", refundAmount, pgNameFor(b)));
        }

        String detail = cancelFee != ruleFee
                ? "{\"cancelFee\":[" + ruleFee + "," + cancelFee + "]}"
                : null;
        auditLogs.save(AuditLog.of(caller.userId(), "booking_refund", b.getPartnerId(), id, b.getCustomerName(), detail));

        return BookingDetailResponse.from(b);
    }

    /** 소유 업체 범위 확인까지 끝낸 조회 (쓰기 메서드 공용) */
    private Booking findScoped(Caller caller, String id) {
        Booking b = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(b.getPartnerId())) throw notFound(id);
        return b;
    }

    /** 결제 행에 복사해 둘 PG사명. 그 통화에 설정이 없으면 '-' */
    private String pgNameFor(Booking b) {
        String currency = b.getPartner() != null ? b.getPartner().getCurrency() : null;
        if (currency == null) return "-";
        return pgSettings.findById(currency).map(PgSetting::getPgName).orElse("-");
    }

    /** 오늘 취소하면 내야 할 취소 수수료 (상품의 cancelRules, 이용일까지 남은 날 기준) */
    private int cancelFeeNow(Booking b) {
        List<Fees.CancelRule> rules = parseCancelRules(b.getProduct() != null ? b.getProduct().getCancelRules() : null);
        long daysLeft = Fees.daysBetween(Fees.jstToday(), b.getUseDate());
        return Fees.cancelFeeFor(b.getAmount(), rules, daysLeft);
    }

    private List<Fees.CancelRule> parseCancelRules(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Fees.CancelRule>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다: " + id);
    }
}
