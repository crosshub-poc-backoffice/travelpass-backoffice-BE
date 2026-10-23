package com.crosshub.backoffice.payment;

import com.crosshub.backoffice.account.AuditLog;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.booking.Payment;
import com.crosshub.backoffice.booking.PaymentRepository;
import com.crosshub.backoffice.common.PageResponse;
import com.crosshub.backoffice.partner.Affiliate;
import com.crosshub.backoffice.partner.AffiliateRepository;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.payment.dto.PaymentDtos.AffiliateShareRequest;
import com.crosshub.backoffice.payment.dto.PaymentDtos.BankRequest;
import com.crosshub.backoffice.payment.dto.PaymentDtos.MyAffiliateResponse;
import com.crosshub.backoffice.payment.dto.PaymentDtos.PaymentRow;
import com.crosshub.backoffice.payment.dto.PaymentDtos.PgSettingRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 설정(통화별 PG)·연맹 몫·연맹 정산 계좌 쓰기 + 결제내역 조회. FE lib/server/payments/service.ts를 1:1로 옮긴 것.
 * PG 수수료율 + 연맹 몫이 업체 수수료율을 넘으면(크로스허브 몫 마이너스) 막는다. 바뀐 값은 작업 이력에 남긴다(계좌번호는 뒤 4자리).
 */
@Service
public class PaymentCommandService {

    /** FE lib/settlements.ts SUPPORTED_CURRENCIES */
    static final Set<String> CURRENCIES = Set.of("JPY", "KRW", "USD", "CNY", "TWD", "HKD", "VND", "EUR");
    static final int PG_NAME_MAX = 50;
    static final int PAY_DAY_MAX = 28;
    static final int TEXT_MAX = 100;   // FE PARTNER_TEXT_MAX
    static final int PAGE_SIZE = 20;   // FE PAGE_SIZE

    private final PgSettingRepository pgSettings;
    private final AffiliateRepository affiliates;
    private final PartnerRepository partners;
    private final PaymentRepository payments;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;

    public PaymentCommandService(PgSettingRepository pgSettings, AffiliateRepository affiliates, PartnerRepository partners,
                                 PaymentRepository payments, AuditLogRepository auditLogs, ObjectMapper objectMapper) {
        this.pgSettings = pgSettings;
        this.affiliates = affiliates;
        this.partners = partners;
        this.payments = payments;
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    /** 통화별 PG 설정 등록·수정. 크로스허브만. 그 통화 업체 중 수수료율 < PG + 연맹 몫이 되는 곳이 있으면 막는다 */
    @Transactional
    public void savePgSetting(Caller caller, String currency, PgSettingRequest req) {
        requireCrosshub(caller);
        String cur = currency == null ? "" : currency.trim().toUpperCase();
        if (!CURRENCIES.contains(cur)) throw error(HttpStatus.BAD_REQUEST, "currency");
        String pgName = req.pgName() == null ? "" : req.pgName().trim();
        if (pgName.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "required");
        if (pgName.length() > PG_NAME_MAX) throw error(HttpStatus.BAD_REQUEST, "tooLong");
        Double rawFee = req.feeRate();
        if (rawFee == null || rawFee.isNaN() || rawFee < 0 || rawFee > 100) throw error(HttpStatus.BAD_REQUEST, "feeRate");
        Integer payDay = req.payDay();
        if (payDay == null || payDay < 1 || payDay > PAY_DAY_MAX) throw error(HttpStatus.BAD_REQUEST, "payDay");
        double feeRate = Math.round(rawFee * 100) / 100.0; // 소수 둘째 자리까지 (3.25%)

        for (Partner p : partners.findAll()) {
            if (!cur.equals(p.getCurrency())) continue;
            int share = p.getProxyAffiliate() != null && p.getProxyAffiliate().getShareRate() != null ? p.getProxyAffiliate().getShareRate() : 0;
            if (exceeds(p.getCommissionRate(), feeRate, share)) throw error(HttpStatus.CONFLICT, "sharesExceedCommission");
        }

        PgSetting before = pgSettings.findById(cur).orElse(null);
        Map<String, Object[]> detail = new LinkedHashMap<>();
        if (before == null || !Objects.equals(before.getPgName(), pgName)) detail.put("pgName", new Object[] {before == null ? null : before.getPgName(), pgName});
        if (before == null || before.getFeeRate() == null || before.getFeeRate() != feeRate) {
            detail.put("feeRate", new Object[] {before == null || before.getFeeRate() == null ? null : jsNumber(before.getFeeRate()), jsNumber(feeRate)});
        }
        if (before == null || !Objects.equals(before.getPayDay(), payDay)) detail.put("payDay", new Object[] {before == null ? null : before.getPayDay(), payDay});

        PgSetting s = before != null ? before : PgSetting.create(cur);
        s.update(pgName, feeRate, payDay);
        pgSettings.saveAndFlush(s);
        audit(caller, "settlement_setting", cur, cur, detail);
    }

    /** 연맹 몫(%p)·정산 계좌 변경. 크로스허브만. 몫과 계좌는 이력을 따로 남긴다 */
    @Transactional
    public void saveAffiliateShare(Caller caller, String id, AffiliateShareRequest req) {
        requireCrosshub(caller);
        Affiliate a = affiliates.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "notFound"));
        Integer shareRate = req.shareRate();
        if (shareRate != null && (shareRate < 0 || shareRate > 100)) throw error(HttpStatus.BAD_REQUEST, "shareRate");
        BankRequest bank = cleanBank(req.bankName(), req.bankAccountNumber(), req.bankAccountHolder());

        Map<String, Object[]> bankDetail = bankDiff(a, bank);
        boolean shareChanged = !Objects.equals(a.getShareRate(), shareRate);
        if (!shareChanged && bankDetail.isEmpty()) return;

        if (shareChanged) {
            Map<String, PgSetting> settings = new LinkedHashMap<>();
            pgSettings.findAll().forEach(s -> settings.put(s.getCurrency(), s));
            for (Partner p : partners.findByProxyAffiliateId(id)) {
                PgSetting s = settings.get(p.getCurrency());
                double pgFee = s != null && s.getFeeRate() != null ? s.getFeeRate() : 0;
                if (exceeds(p.getCommissionRate(), pgFee, shareRate == null ? 0 : shareRate)) {
                    throw error(HttpStatus.CONFLICT, "sharesExceedCommission");
                }
            }
        }
        Integer oldShare = a.getShareRate();
        a.setShareRate(shareRate);
        a.setBank(bank.bankName(), bank.bankAccountNumber(), bank.bankAccountHolder());
        affiliates.saveAndFlush(a);
        if (shareChanged) {
            Map<String, Object[]> d = new LinkedHashMap<>();
            d.put("shareRate", new Object[] {oldShare, shareRate});
            audit(caller, "settlement_share", id, a.getName(), d);
        }
        if (!bankDetail.isEmpty()) audit(caller, "settlement_affiliate_bank", id, a.getName(), bankDetail);
    }

    /** 연맹 계정의 "내 연맹 정보". 연맹이 아니면 404 */
    @Transactional(readOnly = true)
    public MyAffiliateResponse getMyAffiliate(Caller caller) {
        if (!caller.isAffiliate() || caller.affiliateId() == null) throw error(HttpStatus.NOT_FOUND, "notFound");
        Affiliate a = affiliates.findById(caller.affiliateId()).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "notFound"));
        return new MyAffiliateResponse(a.getName(), a.getShareRate(), a.getBankName(), a.getBankAccountNumber(), a.getBankAccountHolder());
    }

    /** 연맹이 자기 정산 계좌를 고친다. 연맹 몫은 크로스허브만 */
    @Transactional
    public void saveMyAffiliateBank(Caller caller, BankRequest req) {
        if (!caller.isAffiliate() || caller.affiliateId() == null) throw error(HttpStatus.FORBIDDEN, "forbidden");
        Affiliate a = affiliates.findById(caller.affiliateId()).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "notFound"));
        BankRequest bank = cleanBank(req.bankName(), req.bankAccountNumber(), req.bankAccountHolder());
        Map<String, Object[]> detail = bankDiff(a, bank);
        if (detail.isEmpty()) return;
        a.setBank(bank.bankName(), bank.bankAccountNumber(), bank.bankAccountHolder());
        affiliates.saveAndFlush(a);
        audit(caller, "settlement_affiliate_bank", a.getId(), a.getName(), detail);
    }

    /** 결제내역. from·to는 JST 날짜(YYYY-MM-DD, to 포함), type은 payment|refund|null. partnerId = 업체 전환(선택) */
    @Transactional(readOnly = true)
    public PageResponse<PaymentRow> listPayments(Caller caller, String from, String to, String type, int page, String partnerId) {
        LocalDate f;
        LocalDate t;
        try {
            f = LocalDate.parse(from);
            t = LocalDate.parse(to);
        } catch (DateTimeParseException | NullPointerException e) {
            throw error(HttpStatus.BAD_REQUEST, "invalidDate");
        }
        if (type != null && !type.isBlank() && !Set.of("payment", "refund").contains(type)) throw error(HttpStatus.BAD_REQUEST, "invalidType");
        boolean anyType = type == null || type.isBlank();

        boolean all = false;
        List<String> ids;
        if (partnerId != null && !partnerId.isBlank()) {
            ids = caller.canAccessPartner(partnerId) ? List.of(partnerId) : List.of();
        } else if (caller.isCrosshub()) {
            all = true;
            ids = List.of("_none_");
        } else {
            ids = caller.partnerIds();
        }
        if (!all && ids.isEmpty()) ids = List.of("_none_");

        ZoneOffset jst = ZoneOffset.ofHours(9);
        int pageNo = Math.max(1, page);
        Page<Object[]> result = payments.search(all, ids, f.atStartOfDay().atOffset(jst), t.plusDays(1).atStartOfDay().atOffset(jst),
                anyType, anyType ? "payment" : type, PageRequest.of(pageNo - 1, PAGE_SIZE));
        List<PaymentRow> rows = result.getContent().stream().map(r -> {
            Payment p = (Payment) r[0];
            return new PaymentRow(p.getId(), p.getCreatedAt(), p.getBookingId(), (String) r[3], (String) r[4],
                    (String) r[1], (String) r[2], p.getType(), p.getAmount(), p.getPg(), p.getPgTransactionId());
        }).toList();
        return PageResponse.of(result, rows);
    }

    // ── 내부 ──

    /** FE sharesExceedCommission: 수수료율이 비어 있는 업체(계약 전)는 검사하지 않는다 */
    static boolean exceeds(Integer commissionRate, double pgFeeRate, int affiliateShareRate) {
        return commissionRate != null && commissionRate < pgFeeRate + affiliateShareRate;
    }

    /** 3.0 → 3, 3.25 → 3.25 (FE 이력과 같은 숫자 모양) */
    static Number jsNumber(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.valueOf((long) v);
        return Double.valueOf(v);
    }

    private static BankRequest cleanBank(String name, String number, String holder) {
        BankRequest b = new BankRequest(trim(name), trim(number), trim(holder));
        for (String s : new String[] {b.bankName(), b.bankAccountNumber(), b.bankAccountHolder()}) {
            if (s != null && s.length() > TEXT_MAX) throw error(HttpStatus.BAD_REQUEST, "tooLong");
        }
        return b;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 바뀐 계좌 칸만. 계좌번호는 뒤 4자리만 */
    static Map<String, Object[]> bankDiff(Affiliate a, BankRequest b) {
        Map<String, Object[]> d = new LinkedHashMap<>();
        if (!Objects.equals(a.getBankName(), b.bankName())) d.put("bankName", new Object[] {a.getBankName(), b.bankName()});
        if (!Objects.equals(a.getBankAccountNumber(), b.bankAccountNumber())) {
            d.put("bankAccountNumber", new Object[] {mask(a.getBankAccountNumber()), mask(b.bankAccountNumber())});
        }
        if (!Objects.equals(a.getBankAccountHolder(), b.bankAccountHolder())) {
            d.put("bankAccountHolder", new Object[] {a.getBankAccountHolder(), b.bankAccountHolder()});
        }
        return d;
    }

    static String mask(String v) {
        if (v == null || v.isEmpty()) return null;
        return "****" + v.substring(Math.max(0, v.length() - 4));
    }

    private static void requireCrosshub(Caller caller) {
        if (!caller.isCrosshub()) throw error(HttpStatus.FORBIDDEN, "forbidden");
    }

    private static PaymentException error(HttpStatus status, String code) {
        return new PaymentException(status, code);
    }

    private void audit(Caller caller, String action, String targetId, String targetName, Map<String, Object[]> detail) {
        try {
            auditLogs.save(AuditLog.of(caller.userId(), action, null, targetId, targetName, objectMapper.writeValueAsString(detail)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

}
