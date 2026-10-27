package com.crosshub.backoffice.settlement;

import com.crosshub.backoffice.account.AuditLog;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.booking.Booking;
import com.crosshub.backoffice.booking.BookingRepository;
import com.crosshub.backoffice.booking.Fees;
import com.crosshub.backoffice.partner.Affiliate;
import com.crosshub.backoffice.partner.AffiliateRepository;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.payment.PgSetting;
import com.crosshub.backoffice.payment.PgSettingRepository;
import com.crosshub.backoffice.settlement.dto.SettlementRows.AffiliateRow;
import com.crosshub.backoffice.settlement.dto.SettlementRows.Distribution;
import com.crosshub.backoffice.settlement.dto.SettlementRows.Line;
import com.crosshub.backoffice.settlement.dto.SettlementRows.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월별 정산. FE lib/server/settlements/service.ts를 1:1로 옮긴 것 — 돈 계산이라 식·반올림을 그대로 둔다.
 * 지급대기 동안은 예약에서 그때그때 계산하고, 크로스허브가 지급완료하는 순간 금액을 Settlement 행에 굳힌다.
 * 지급 취소하면 행을 지워 다시 계산 상태로 돌아간다. 연맹 몫(AffiliateSettlement)도 같은 방식.
 * 정산은 이용완료 기준만: 이용일이 그 달인 이용완료 예약(취소·환불은 취소 수수료).
 */
@Service
public class SettlementService {

    static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final Pattern DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final PartnerRepository partners;
    private final AffiliateRepository affiliates;
    private final BookingRepository bookings;
    private final PgSettingRepository pgSettings;
    private final SettlementRepository settlements;
    private final AffiliateSettlementRepository affiliateSettlements;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;

    public SettlementService(PartnerRepository partners, AffiliateRepository affiliates, BookingRepository bookings,
                             PgSettingRepository pgSettings, SettlementRepository settlements,
                             AffiliateSettlementRepository affiliateSettlements, AuditLogRepository auditLogs,
                             ObjectMapper objectMapper) {
        this.partners = partners;
        this.affiliates = affiliates;
        this.bookings = bookings;
        this.pgSettings = pgSettings;
        this.settlements = settlements;
        this.affiliateSettlements = affiliateSettlements;
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    // ── 조회 ──────────────────────────────────────────────

    /**
     * 그 달 업체별 정산. partnerId가 있으면(업체 전환) 그 업체만 — 범위 밖이면 빈 목록.
     * 크로스허브가 아니면 예약별 연맹 몫·PG 수수료를 빼고 준다 — 업체 수수료에서 이 둘을 빼면 크로스허브 마진이 나온다
     * (합계 칸 distribution 은 aggregate 가 이미 크로스허브에게만 준다)
     */
    @Transactional(readOnly = true)
    public List<Row> listMonthly(Caller caller, String month, String partnerId) {
        if (month == null || !MONTH.matcher(month).matches()) throw notFound();
        List<Row> rows = aggregate(scope(caller, partnerId), month, caller.isCrosshub());
        if (caller.isCrosshub()) return rows;
        return rows.stream().map(r -> new Row(r.partnerId(), r.partnerName(), r.currency(), r.commissionRateLabel(),
                r.gross(), r.commission(), r.net(), r.bookingCount(), r.status(), r.payDate(),
                r.lines().stream().map(Line::withoutDistribution).toList(), r.bankAccount(), r.distribution())).toList();
    }

    /** 그 달 연맹 몫. 크로스허브는 연맹 전체, 연맹은 자기 것만, 업체는 없음 */
    @Transactional(readOnly = true)
    public List<AffiliateRow> listAffiliates(Caller caller, String month) {
        if (month == null || !MONTH.matcher(month).matches()) throw notFound();
        if ("partner".equals(caller.role())) return List.of();
        if (caller.isAffiliate()) {
            if (caller.affiliateId() == null) return List.of();
            return affiliates.findById(caller.affiliateId()).map(a -> aggregateAffiliates(List.of(a), month)).orElse(List.of());
        }
        return aggregateAffiliates(affiliates.findAll(Sort.by("name")), month);
    }

    // ── 지급 처리 (크로스허브만) ──────────────────────────

    @Transactional
    public void markPaid(Caller caller, String partnerId, String period, String payDate) {
        requireCrosshub(caller);
        LocalDate pay = validPeriodAndDay(partnerId, period, payDate);
        if (!isMonthEnded(period)) throw new SettlementException(HttpStatus.CONFLICT, "monthNotEnded");
        if (hasUnfinishedBookings(List.of(partnerId), period)) throw new SettlementException(HttpStatus.CONFLICT, "unfinishedBookings");
        List<Row> rows = aggregate(List.of(partnerId), period, false);
        if (rows.isEmpty()) throw notFound();
        Row row = rows.get(0);
        if ("paid".equals(row.status())) throw new SettlementException(HttpStatus.CONFLICT, "alreadyPaid");

        // Settlement.commissionRate는 정수 한 칸이라, 섞인 달("12~15")은 첫 요율만 남긴다 (FE parseInt와 같음)
        Settlement s = Settlement.paid(partnerId, period, row.gross(), leadingInt(row.commissionRateLabel()),
                row.commission(), row.net(), row.bookingCount(), pay);
        insertOnce(() -> settlements.saveAndFlush(s));
        audit(caller, "settlement_pay", partnerId, period, row.partnerName(),
                detail("period", null, period, "payDate", null, payDate, "net", null, row.net()));
    }

    @Transactional
    public void unmarkPaid(Caller caller, String partnerId, String period) {
        requireCrosshub(caller);
        if (partnerId == null || partnerId.isBlank() || period == null || !MONTH.matcher(period).matches()) throw notFound();
        Settlement existing = settlements.findByPartnerIdAndPeriod(partnerId, period).orElseThrow(SettlementService::notFound);
        String name = partners.findById(partnerId).map(Partner::getName).orElse(null);
        settlements.delete(existing);
        audit(caller, "settlement_unpay", partnerId, period, name,
                detail("period", period, null, "payDate", dateOrNull(existing.getPayDate()), null));
    }

    @Transactional
    public void markAffiliatePaid(Caller caller, String affiliateId, String period, String currency, String payDate) {
        requireCrosshub(caller);
        LocalDate pay = validPeriodAndDay(affiliateId, period, payDate);
        if (currency == null || !CURRENCY.matcher(currency).matches()) throw notFound();
        if (!isMonthEnded(period)) throw new SettlementException(HttpStatus.CONFLICT, "monthNotEnded");
        Affiliate a = affiliates.findById(affiliateId).orElseThrow(SettlementService::notFound);
        List<String> proxyIds = partners.findByProxyAffiliateId(affiliateId).stream().map(Partner::getId).toList();
        if (hasUnfinishedBookings(proxyIds, period)) throw new SettlementException(HttpStatus.CONFLICT, "unfinishedBookings");
        AffiliateRow row = aggregateAffiliates(List.of(a), period).stream()
                .filter(r -> r.currency().equals(currency)).findFirst().orElseThrow(SettlementService::notFound);
        if ("paid".equals(row.status())) throw new SettlementException(HttpStatus.CONFLICT, "alreadyPaid");

        AffiliateSettlement s = AffiliateSettlement.paid(affiliateId, period, currency, row.amount(), row.partnerCount(), pay);
        insertOnce(() -> affiliateSettlements.saveAndFlush(s));
        audit(caller, "settlement_affiliate_pay", null, period, a.getName(),
                detail("period", null, period, "currency", null, currency, "payDate", null, payDate, "amount", null, row.amount()));
    }

    @Transactional
    public void unmarkAffiliatePaid(Caller caller, String affiliateId, String period, String currency) {
        requireCrosshub(caller);
        if (affiliateId == null || affiliateId.isBlank() || period == null || !MONTH.matcher(period).matches()
                || currency == null || !CURRENCY.matcher(currency).matches()) throw notFound();
        AffiliateSettlement existing = affiliateSettlements.findByAffiliateIdAndPeriodAndCurrency(affiliateId, period, currency)
                .orElseThrow(SettlementService::notFound);
        String name = affiliates.findById(affiliateId).map(Affiliate::getName).orElse(null);
        affiliateSettlements.delete(existing);
        audit(caller, "settlement_affiliate_unpay", null, period, name,
                detail("period", period, null, "currency", currency, null, "payDate", dateOrNull(existing.getPayDate()), null));
    }

    // ── 계산 (FE aggregate·toLine·aggregateAffiliates) ─────

    /** 한 업체·한 달 정산 행들. 업체 이름순 */
    List<Row> aggregate(List<String> partnerIds, String month, boolean forCrosshub) {
        if (partnerIds.isEmpty()) return List.of();
        LocalDate start = LocalDate.parse(month + "-01");
        LocalDate end = start.plusMonths(1);

        List<Partner> ps = partners.findByIdInOrderByNameAsc(partnerIds);
        Map<String, PgSetting> settings = pgSettings.findAll().stream()
                .collect(Collectors.toMap(PgSetting::getCurrency, Function.identity()));
        List<Booking> bs = bookings.findForSettlement(partnerIds, start, end);
        List<Settlement> saved = settlements.findByPeriodInAndPartnerIdIn(List.of(month), partnerIds);

        List<Row> rows = new ArrayList<>();
        for (Partner p : ps) {
            PgSetting setting = settings.get(p.getCurrency());
            List<Line> lines = bs.stream().filter(b -> b.getPartnerId().equals(p.getId()))
                    .map(b -> toLine(b, month)).filter(l -> l != null).toList();
            Optional<Settlement> paid = saved.stream().filter(s -> s.getPartnerId().equals(p.getId())).findFirst();

            // 지급완료면 그때 굳힌 숫자, 아니면 지금 예약 기준으로 계산한 숫자
            int gross = paid.map(Settlement::getGross).orElseGet(() -> lines.stream().mapToInt(Line::base).sum());
            int commission = paid.map(Settlement::getCommission).orElseGet(() -> lines.stream().mapToInt(Line::commission).sum());
            int net = paid.map(Settlement::getNet).orElseGet(() -> lines.stream().mapToInt(Line::net).sum());
            int bookingCount = paid.map(Settlement::getBookingCount).orElse(lines.size());

            Distribution distribution = null;
            if (forCrosshub) {
                int pg = lines.stream().mapToInt(Line::pg).sum();
                int affiliate = lines.stream().mapToInt(Line::affiliate).sum();
                Affiliate proxy = p.getProxyAffiliate();
                String pgLabel = rateLabel(lines.stream().map(Line::pgFeeRate).toList());
                String shareLabel = rateLabel(lines.stream().map(l -> (double) l.affiliateShareRate()).toList());
                distribution = new Distribution(
                        setting != null ? setting.getPgName() : null,
                        pgLabel != null ? pgLabel : num(setting != null && setting.getFeeRate() != null ? setting.getFeeRate() : 0),
                        pg,
                        proxy != null ? proxy.getName() : null,
                        shareLabel != null ? shareLabel : num(proxy != null && proxy.getShareRate() != null ? proxy.getShareRate() : 0),
                        affiliate,
                        commission - pg - affiliate);
            }
            String rateLabel = rateLabel(lines.stream().map(l -> (double) l.commissionRate()).toList());
            rows.add(new Row(
                    p.getId(), p.getName(), p.getCurrency(),
                    rateLabel != null ? rateLabel : (p.getCommissionRate() == null ? "-" : String.valueOf(p.getCommissionRate())),
                    gross, commission, net, bookingCount,
                    paid.isPresent() ? "paid" : "pending",
                    paid.map(s -> dateOrNull(s.getPayDate())).orElse(null),
                    lines,
                    forCrosshub ? bankLabel(p.getBankName(), p.getBankAccountNumber(), p.getBankAccountHolder()) : null,
                    distribution));
        }
        return rows;
    }

    /** 예약 한 건이 그 달 정산에 들어가는지, 들어가면 어떤 금액으로 */
    static Line toLine(Booking b, String month) {
        if (!b.getUseDate().toString().startsWith(month)) return null;
        boolean isCancel = "canceled".equals(b.getStatus()) || "refunded".equals(b.getStatus());
        int base;
        int commission;
        int net;
        if (isCancel) {
            int fee = b.getCancelFee() == null ? 0 : b.getCancelFee();
            if (fee <= 0) return null;
            Fees.Split split = Fees.splitAmount(fee, b.getCommissionRate());
            base = fee;
            commission = split.commission();
            net = split.net();
        } else if ("completed".equals(b.getStatus())) {
            base = b.getAmount();
            commission = b.getCommission();
            net = b.getNet();
        } else {
            return null;
        }
        // 배분은 정산 기준 금액에 예약 때 박힌 요율을 곱한다 (업체 수수료와 같은 반올림)
        int affiliate = (int) Math.round((long) base * b.getAffiliateShareRate() / 100.0); // int 곱셈은 약 2천만엔×100%에서 넘친다 (Fees 와 같음)
        int pg = (int) Math.round(base * b.getPgFeeRate() / 100);
        return new Line(b.getId(), b.getProductId(), b.getProduct() != null ? b.getProduct().getName() : null,
                b.getUseDate().toString(), b.getStatus(), base, b.getCommissionRate(), commission, net, isCancel,
                b.getAffiliateShareRate(), affiliate, b.getPgFeeRate(), pg);
    }

    private List<AffiliateRow> aggregateAffiliates(List<Affiliate> list, String month) {
        Map<String, List<String>> proxyIds = new LinkedHashMap<>();
        for (Affiliate a : list) {
            proxyIds.put(a.getId(), partners.findByProxyAffiliateId(a.getId()).stream().map(Partner::getId).toList());
        }
        List<String> allIds = proxyIds.values().stream().flatMap(List::stream).toList();
        List<Row> partnerRows = aggregate(allIds, month, false);
        List<AffiliateSettlement> saved = list.isEmpty() ? List.of()
                : affiliateSettlements.findByPeriodAndAffiliateIdIn(month, proxyIds.keySet());

        List<AffiliateRow> rows = new ArrayList<>();
        for (Affiliate a : list) {
            List<Row> mine = partnerRows.stream().filter(r -> proxyIds.get(a.getId()).contains(r.partnerId())).toList();
            // 대행 업체 통화가 섞이면 통화마다 1행
            for (String currency : mine.stream().map(Row::currency).collect(Collectors.toCollection(LinkedHashSet::new))) {
                List<Row> rs = mine.stream().filter(r -> r.currency().equals(currency)).toList();
                List<Line> lines = rs.stream().flatMap(r -> r.lines().stream()).toList();
                Optional<AffiliateSettlement> paid = saved.stream()
                        .filter(s -> s.getAffiliateId().equals(a.getId()) && s.getCurrency().equals(currency)).findFirst();
                String label = rateLabel(lines.stream().map(l -> (double) l.affiliateShareRate()).toList());
                rows.add(new AffiliateRow(
                        a.getId(), a.getName(), currency,
                        label != null ? label : num(a.getShareRate() == null ? 0 : a.getShareRate()),
                        paid.map(AffiliateSettlement::getAmount).orElseGet(() -> lines.stream().mapToInt(Line::affiliate).sum()),
                        paid.map(AffiliateSettlement::getPartnerCount).orElseGet(() -> (int) rs.stream().filter(r -> r.gross() > 0).count()),
                        paid.isPresent() ? "paid" : "pending",
                        paid.map(s -> dateOrNull(s.getPayDate())).orElse(null),
                        bankLabel(a.getBankName(), a.getBankAccountNumber(), a.getBankAccountHolder())));
            }
        }
        return rows;
    }

    // ── 공통 ──────────────────────────────────────────────

    /** 조회 범위: partnerId(업체 전환)가 있으면 그 업체(권한 있을 때만), 없으면 크로스허브 전체·그 외 관리 업체 */
    private List<String> scope(Caller caller, String partnerId) {
        if (partnerId != null && !partnerId.isBlank()) {
            return caller.canAccessPartner(partnerId) ? List.of(partnerId) : List.of();
        }
        if (caller.isCrosshub()) return partners.findAll().stream().map(Partner::getId).toList();
        return caller.partnerIds();
    }

    private boolean hasUnfinishedBookings(List<String> partnerIds, String month) {
        if (partnerIds.isEmpty()) return false;
        LocalDate start = LocalDate.parse(month + "-01");
        return bookings.countUnfinished(partnerIds, start, start.plusMonths(1)) > 0;
    }

    /** 그 달이 끝났는지 (일본 시간). 끝나기 전에 지급완료하면 뒤늦게 이용완료된 건이 빠진다 */
    static boolean isMonthEnded(String month) {
        return month.compareTo(Fees.jstToday().toString().substring(0, 7)) < 0;
    }

    /** FE rateLabel: 소수 둘째 자리 반올림 후 중복 제거. 하나면 "15", 섞이면 "12~15", 없으면 null */
    static String rateLabel(List<Double> rates) {
        TreeSet<BigDecimal> uniq = new TreeSet<>();
        for (Double r : rates) uniq.add(BigDecimal.valueOf(Math.round(r * 100) / 100.0));
        if (uniq.isEmpty()) return null;
        return uniq.size() == 1 ? fmt(uniq.first()) : fmt(uniq.first()) + "~" + fmt(uniq.last());
    }

    /** JS String(number)처럼: 15 → "15", 3.25 → "3.25" */
    static String num(double v) {
        return fmt(BigDecimal.valueOf(v));
    }

    private static String fmt(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    /** "12~15" → 12, "-" → 0 (FE Number.parseInt(..) || 0) */
    static int leadingInt(String label) {
        java.util.regex.Matcher m = Pattern.compile("^\\s*(-?\\d+)").matcher(label == null ? "" : label);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 정산 계좌 한 줄 "은행 계좌번호 (예금주)". 은행·계좌번호가 둘 다 비면 null */
    static String bankLabel(String bankName, String number, String holder) {
        if (isEmpty(bankName) && isEmpty(number)) return null;
        return Arrays.asList(bankName, number, isEmpty(holder) ? null : "(" + holder + ")").stream()
                .filter(s -> !isEmpty(s)).collect(Collectors.joining(" "));
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String dateOrNull(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static LocalDate validPeriodAndDay(String id, String period, String payDate) {
        if (id == null || id.isBlank() || period == null || !MONTH.matcher(period).matches()
                || payDate == null || !DAY.matcher(payDate).matches()) throw notFound();
        try {
            return LocalDate.parse(payDate);
        } catch (DateTimeParseException e) {
            throw notFound();
        }
    }

    /** 거의 동시에 두 번 지급하면 유니크 제약에 걸린다 → "이미 지급완료" */
    private static void insertOnce(Runnable insert) {
        try {
            insert.run();
        } catch (DataIntegrityViolationException e) {
            throw new SettlementException(HttpStatus.CONFLICT, "alreadyPaid");
        }
    }

    private static void requireCrosshub(Caller caller) {
        if (!caller.isCrosshub()) throw new SettlementException(HttpStatus.FORBIDDEN, "forbidden");
    }

    private static SettlementException notFound() {
        return new SettlementException(HttpStatus.NOT_FOUND, "notFound");
    }

    /** detail(k1, before1, after1, k2, ...) → {"k1":[before1, after1], ...} */
    private static Map<String, Object[]> detail(Object... kv) {
        Map<String, Object[]> m = new LinkedHashMap<>();
        for (int i = 0; i + 2 < kv.length; i += 3) m.put((String) kv[i], new Object[] {kv[i + 1], kv[i + 2]});
        return m;
    }

    private void audit(Caller caller, String action, String partnerId, String period, String name, Map<String, Object[]> d) {
        try {
            auditLogs.save(AuditLog.of(caller.userId(), action, partnerId, period, name, objectMapper.writeValueAsString(d)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
