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
import com.crosshub.backoffice.notification.Notifier;
import com.crosshub.backoffice.product.translation.ProductTranslationRepository;
import com.crosshub.backoffice.payment.PgSetting;
import com.crosshub.backoffice.payment.PgSettingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    /** DB BookingStatus enum 값. 모르는 값이 쿼리로 가면 enum 변환에서 500이 나니 먼저 거른다 */
    private static final java.util.Set<String> STATUSES =
            java.util.Set.of("pending", "quoted", "confirmed", "rejected", "completed", "canceled", "refunded", "withdrawn");

    private final BookingRepository repository;
    private final PaymentRepository payments;
    private final PgSettingRepository pgSettings;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;
    private final Notifier notifier;
    private final ProductTranslationRepository translations;

    public BookingService(
            BookingRepository repository,
            PaymentRepository payments,
            PgSettingRepository pgSettings,
            AuditLogRepository auditLogs,
            ObjectMapper objectMapper,
            Notifier notifier,
            ProductTranslationRepository translations) {
        this.repository = repository;
        this.payments = payments;
        this.pgSettings = pgSettings;
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
        this.notifier = notifier;
        this.translations = translations;
    }

    /** 대시보드: 업체가 견적을 보내야 하는(업체확인중) 예약 오래된 순 N건 + 환불 처리할(취소됨) 예약 3건, 각 전체 건수 */
    public record Attention(List<BookingSummaryResponse> rows, long total, List<BookingSummaryResponse> refundRows, long refundTotal) {}

    @Transactional(readOnly = true)
    public Attention attention(Caller caller, int limit, String partnerId) {
        List<String> narrowed = caller.narrowedPartnerIds(partnerId);
        boolean all = narrowed == null;
        List<String> pids = all ? List.of("_none_") : narrowed;
        Page<Booking> pending = repository.findByScopeAndStatus(all, pids, "pending",
                PageRequest.of(0, Math.max(1, Math.min(limit, 50)), Sort.by(Sort.Direction.ASC, "requestedAt")));
        // 취소 시각은 따로 저장하지 않아서, 마지막으로 바뀐 순(= 취소된 순)으로 본다
        Page<Booking> refunds = repository.findByScopeAndStatus(all, pids, "canceled",
                PageRequest.of(0, 3, Sort.by(Sort.Direction.ASC, "updatedAt")));
        return new Attention(pending.getContent().stream().map(BookingSummaryResponse::from).toList(), pending.getTotalElements(),
                refunds.getContent().stream().map(BookingSummaryResponse::from).toList(), refunds.getTotalElements());
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

        if (status != null && !status.isBlank() && !"active".equals(status) && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidStatus");
        }
        List<String> statuses = "active".equals(status) ? ACTIVE_STATUSES
                : (status != null && !status.isBlank() ? List.of(status) : null);
        boolean anyStatus = statuses == null;
        // status는 Postgres enum이라 더미도 유효한 enum 값이어야 한다("pending"). anyStatus=true면 어차피 안 쓰임.
        List<String> statusParam = anyStatus ? List.of("pending") : statuses;

        boolean anyProduct = (productId == null || productId.isBlank());
        String productParam = anyProduct ? "_none_" : productId;

        boolean noKeyword = (keyword == null || keyword.isBlank());
        String kw = noKeyword ? "_none_" : "%" + keyword.trim().toLowerCase() + "%";
        // 상품명 검색은 번역된 이름도 매칭 (목록 화면엔 보는 사람 언어의 상품명이 보이므로)
        List<String> translatedIds = noKeyword ? List.of("_none_") : translations.findProductIdsByTranslatedName(kw);
        if (translatedIds.isEmpty()) translatedIds = List.of("_none_");

        // 주의: List.of(...).contains(null)은 NPE. status가 null일 수 있으니 null 가드.
        boolean upcomingFirst = "active".equals(status)
                || (status != null && ACTIVE_STATUSES.contains(status));
        Sort sort = Sort.by(
                new Sort.Order(upcomingFirst ? Sort.Direction.ASC : Sort.Direction.DESC, "useDate"),
                new Sort.Order(Sort.Direction.ASC, "requestedAt"));
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), PAGE_SIZE, sort);

        Page<Booking> result = repository.search(
                allPartners, partnerIds, anyStatus, statusParam,
                anyProduct, productParam, noKeyword, kw, translatedIds, pageable);
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

    /** 업체가 고객 신청에 견적서를 보낸다. 업체확인중일 때만 (규칙은 Booking.sendQuote) */
    @Transactional
    public BookingDetailResponse sendQuote(Caller caller, String id, BookingQuoteRequest req) {
        Booking b = findScoped(caller, id);
        b.sendQuote(req.amount(), req.message());
        return BookingDetailResponse.from(b);
    }

    /** 고객이 견적에 응답한다(관리자 화면에서 대신 눌러 시뮬레이션). 고객확인중일 때만 (규칙은 Booking.respondToQuote) */
    @Transactional
    public BookingDetailResponse respondAsCustomer(Caller caller, String id, BookingDecisionRequest req) {
        Booking b = findScoped(caller, id);
        boolean paid = b.respondToQuote(req.decision());
        // 수락 = 견적 금액으로 결제됨 → 결제 행을 남긴다
        if (paid) payments.save(Payment.of(id, "payment", b.getAmount(), pgNameFor(b)));
        notifyQuoteResponded(b, req.decision());
        return BookingDetailResponse.from(b);
    }

    /** 견적 거절, 이용완료 처리, 예약 취소. reason은 거절·취소일 때만 쓴다(선택 입력). 규칙은 Booking.changeStatus */
    @Transactional
    public BookingDetailResponse setStatus(Caller caller, String id, BookingStatusRequest req) {
        Booking b = findScoped(caller, id);
        b.changeStatus(req.status(), req.reason(), () -> cancelFeeNow(b));
        return BookingDetailResponse.from(b);
    }

    /**
     * 환불 완료 처리. 취소할 때 취소표로 계산해 둔 수수료를 업체가 확인하고, 필요하면 낮춰서(깎아주기만) 환불한다.
     * 수수료를 올리는 건 막는다 — 고객이 미리 본 취소표보다 더 떼면 분쟁이 된다. 처리 내역은 작업 이력에 남긴다.
     * 규칙은 Booking.refund
     */
    @Transactional
    public BookingDetailResponse completeRefund(Caller caller, String id, BookingRefundRequest req) {
        Booking b = findScoped(caller, id);
        int ruleFee = b.getCancelFee() != null ? b.getCancelFee() : 0;

        // 받은 돈(결제 − 이미 환불). 받은 돈이 없으면(견적 전 취소) 환불 행도 없다
        int paid = payments.findByBookingId(id).stream()
                .mapToInt(p -> "payment".equals(p.getType()) ? p.getAmount() : -p.getAmount())
                .sum();
        int refundAmount = b.refund(req == null ? null : req.cancelFee(), paid);

        if (refundAmount > 0) {
            payments.save(Payment.of(id, "refund", refundAmount, pgNameFor(b)));
        }

        int cancelFee = b.getCancelFee();
        String detail = cancelFee != ruleFee
                ? "{\"cancelFee\":[" + ruleFee + "," + cancelFee + "]}"
                : null;
        auditLogs.save(AuditLog.of(caller.userId(), "booking_refund", b.getPartnerId(), id, b.getCustomerName(), detail));

        return BookingDetailResponse.from(b);
    }

    /** 고객이 견적에 응답하면 업체 계정에게 알림 (고객용 API 로 응답해도 같다) */
    void notifyQuoteResponded(Booking b, String decision) {
        notifier.notify(notifier.partnerUserIds(b.getPartnerId()), "booking_quote_responded", b.getId() + ":" + decision,
                java.util.Map.of("bookingId", b.getId(), "decision", decision));
    }

    /** 소유 업체 범위 확인까지 끝낸 조회 (쓰기 메서드 공용). 행 잠금을 걸어 같은 예약의 동시 상태 변경을 줄 세운다 */
    private Booking findScoped(Caller caller, String id) {
        Booking b = repository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(b.getPartnerId())) throw notFound(id);
        return b;
    }

    /** 결제 행에 복사해 둘 PG사명. 그 통화에 설정이 없으면 '-' */
    String pgNameFor(Booking b) {   // 고객용 서비스(CustomerBookingService)도 쓴다
        String currency = b.getPartner() != null ? b.getPartner().getCurrency() : null;
        if (currency == null) return "-";
        return pgSettings.findById(currency).map(PgSetting::getPgName).orElse("-");
    }

    /** 오늘 취소하면 내야 할 취소 수수료 (상품의 cancelRules, 이용일까지 남은 날 기준) */
    int cancelFeeNow(Booking b) {   // 고객용 서비스(CustomerBookingService)도 쓴다
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
