package com.crosshub.backoffice.partner;

import com.crosshub.backoffice.account.AuditLog;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.UserAccountRepository;
import com.crosshub.backoffice.booking.BookingRepository;
import com.crosshub.backoffice.partner.dto.PartnerResponse;
import com.crosshub.backoffice.partner.dto.PartnerUpsertRequest;
import com.crosshub.backoffice.payment.PgSetting;
import com.crosshub.backoffice.payment.PgSettingRepository;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업체 등록·수정·활성화. FE lib/server/partners/service.ts(createPartner·updatePartner·togglePartnerActive)를 1:1로 옮긴 것.
 *  - 등록: 크로스허브(운영방식 선택) · 연맹(항상 자기 대행 업체). 업체 계정은 못 한다.
 *  - 수정: 관리 범위 안의 업체. 수수료율·통화·운영방식은 크로스허브만, 내부 메모는 업체 계정이 못 고친다.
 *  - 활성화 토글: 크로스허브만. 끄면 노출중 상품을 숨김(hiddenByPartnerOff 표시), 켤 때 restoreProducts면 되돌린다.
 * 실패는 PartnerException(code = FE 에러 키).
 */
@Service
public class PartnerCommandService {

    static final int NAME_MAX = 50;      // FE PARTNER_NAME_MAX
    static final int TEXT_MAX = 100;     // FE PARTNER_TEXT_MAX
    static final int MEMO_MAX = 1000;    // FE PARTNER_MEMO_MAX (주소도 같은 한도)
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final PartnerRepository partners;
    private final AffiliateRepository affiliates;
    private final ProductRepository products;
    private final BookingRepository bookings;
    private final UserAccountRepository users;
    private final PgSettingRepository pgSettings;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;

    public PartnerCommandService(PartnerRepository partners, AffiliateRepository affiliates, ProductRepository products,
                                 BookingRepository bookings, UserAccountRepository users, PgSettingRepository pgSettings,
                                 AuditLogRepository auditLogs, ObjectMapper objectMapper) {
        this.partners = partners;
        this.affiliates = affiliates;
        this.products = products;
        this.bookings = bookings;
        this.users = users;
        this.pgSettings = pgSettings;
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PartnerResponse create(Caller caller, PartnerUpsertRequest raw) {
        PartnerUpsertRequest in = validate(raw);
        // 연맹이 등록하면 항상 자기 대행 업체. 크로스허브만 운영방식을 고른다
        String proxyAffiliateId;
        if (caller.isAffiliate()) proxyAffiliateId = caller.affiliateId();
        else if (caller.isCrosshub()) proxyAffiliateId = in.proxyAffiliateId();
        else throw new PartnerException(HttpStatus.FORBIDDEN, "forbidden");

        if (caller.isAffiliate() && proxyAffiliateId == null) throw new PartnerException(HttpStatus.BAD_REQUEST, "invalidOrg");
        if (proxyAffiliateId != null && !affiliates.existsById(proxyAffiliateId)) {
            throw new PartnerException(HttpStatus.BAD_REQUEST, "invalidOrg");
        }
        if (partners.existsByNameIgnoreCase(in.name())) throw new PartnerException(HttpStatus.CONFLICT, "partnerNameTaken");
        if (caller.isCrosshub() && commissionBelowShares(in.commissionRate(), in.currency(), proxyAffiliateId)) {
            throw new PartnerException(HttpStatus.CONFLICT, "commissionBelowShares");
        }

        // 통화는 크로스허브만 정한다. 다른 역할이 등록하면 DB 기본값(JPY)
        Partner p = Partner.create(in.name(), proxyAffiliateId, caller.isCrosshub() ? in.currency() : "JPY");
        apply(p, in, caller);
        partners.saveAndFlush(p);
        auditLogs.save(AuditLog.of(caller.userId(), "partner_create", p.getId(), p.getId(), p.getName()));
        return toResponse(p, proxyAffiliateId);
    }

    @Transactional
    public PartnerResponse update(Caller caller, String id, PartnerUpsertRequest raw) {
        PartnerUpsertRequest in = validate(raw);
        Partner p = partners.findById(id).orElseThrow(() -> notFound());
        if (!caller.canAccessPartner(p.getId())) throw notFound();

        boolean hasBookings = bookings.existsForPartner(p.getId());
        // 통화는 예약이 생긴 뒤에는 못 바꾼다 — 이미 그 통화로 결제·정산된 금액이 있어서
        if (caller.isCrosshub() && !Objects.equals(in.currency(), p.getCurrency()) && hasBookings) {
            throw new PartnerException(HttpStatus.CONFLICT, "currencyLocked");
        }
        // 운영방식은 크로스허브만 바꾼다. 연맹이 보낸 값은 무시한다
        String proxyAffiliateId = caller.isCrosshub() ? in.proxyAffiliateId() : p.getProxyAffiliateId();
        if (!Objects.equals(proxyAffiliateId, p.getProxyAffiliateId())) {
            // 예약이 생긴 뒤에는 못 바꾼다 — 연맹 몫이 새 연맹으로 넘어가서
            if (hasBookings) throw new PartnerException(HttpStatus.CONFLICT, "proxyLocked");
            if (proxyAffiliateId != null && !affiliates.existsById(proxyAffiliateId)) {
                throw new PartnerException(HttpStatus.BAD_REQUEST, "invalidOrg");
            }
            // 대행 업체는 계정 없이 연맹이 처리한다
            if (proxyAffiliateId != null && users.countByPartnerId(p.getId()) > 0) {
                throw new PartnerException(HttpStatus.CONFLICT, "partnerHasAccounts");
            }
        }
        if (partners.existsByNameIgnoreCaseAndIdNot(in.name(), id)) throw new PartnerException(HttpStatus.CONFLICT, "partnerNameTaken");
        if (caller.isCrosshub() && commissionBelowShares(in.commissionRate(), in.currency(), proxyAffiliateId)) {
            throw new PartnerException(HttpStatus.CONFLICT, "commissionBelowShares");
        }

        // 바뀐 값 비교용 스냅샷 (apply 전에)
        Integer oldRate = p.getCommissionRate();
        Map<String, String> oldBank = bankFields(p);
        Map<String, String> oldInfo = infoFields(p);

        p.setProxyAffiliateId(proxyAffiliateId);
        if (caller.isCrosshub()) p.setCurrency(in.currency());
        apply(p, in, caller);
        p.setUpdatedAt(OffsetDateTime.now());
        partners.saveAndFlush(p);
        // 업체 계정 이름은 업체명을 그대로 쓴다
        users.renameByPartnerId(p.getId(), in.name(), OffsetDateTime.now());

        // 수수료율·정산 계좌·사업자 정보가 바뀔 때만 작업 이력 (이전값·새값). 담당자·연락처는 남기지 않는다
        if (caller.isCrosshub() && !Objects.equals(oldRate, p.getCommissionRate())) {
            Map<String, Object[]> d = new LinkedHashMap<>();
            d.put("commissionRate", new Object[] {oldRate, p.getCommissionRate()});
            audit(caller, "partner_commission", p, d);
        }
        Map<String, Object[]> bank = diff(oldBank, bankFields(p), k -> "bankAccountNumber".equals(k));
        if (!bank.isEmpty()) audit(caller, "partner_bank", p, bank);
        Map<String, Object[]> info = diff(oldInfo, infoFields(p), k -> false);
        if (!info.isEmpty()) audit(caller, "partner_info", p, info);

        return toResponse(p, proxyAffiliateId);
    }

    /**
     * 활성화·비활성화. 크로스허브만. 비활성 = 새로 파는 것만 멈춘다(노출중 → 숨김 + hiddenByPartnerOff 표시).
     * 다시 켤 때 restoreProducts면 표시해 둔 상품만 노출중으로 되돌린다. 어느 쪽이든 켜면 표시는 지운다.
     */
    @Transactional
    public PartnerResponse toggleActive(Caller caller, String id, boolean restoreProducts) {
        if (!caller.isCrosshub()) throw new PartnerException(HttpStatus.FORBIDDEN, "forbidden");
        Partner p = partners.findById(id).orElseThrow(() -> notFound());

        boolean active = !p.isActive();
        String from = active ? "hidden" : "live";
        String to = active ? "live" : "hidden";
        OffsetDateTime now = OffsetDateTime.now();

        List<Product> moved = active && !restoreProducts
                ? List.of()
                : active
                    ? products.findByPartnerIdAndStatusAndHiddenByPartnerOffTrue(id, from)
                    : products.findByPartnerIdAndStatus(id, from);

        p.setActive(active);
        p.setUpdatedAt(now);
        partners.save(p);
        auditLogs.save(AuditLog.of(caller.userId(), active ? "partner_activate" : "partner_deactivate", id, id, p.getName()));

        for (Product prod : moved) {
            if (active) prod.restoreAfterPartnerOn(); else prod.hideForPartnerOff();
            prod.setUpdatedAt(now);
            auditLogs.save(AuditLog.of(caller.userId(), "product_status", id, prod.getId(), prod.getName(),
                    "{\"status\":[\"" + from + "\",\"" + to + "\"]}"));
        }
        // 되돌리지 않고 켜면 표시만 지운다 (그 뒤로는 업체가 직접 숨긴 상품과 같다)
        if (active && !restoreProducts) {
            for (Product prod : products.findByPartnerIdAndHiddenByPartnerOffTrue(id)) {
                prod.clearPartnerOffMark();
                prod.setUpdatedAt(now);
            }
        }
        products.flush();
        return toResponse(p, p.getProxyAffiliateId());
    }

    // ── 내부 ──────────────────────────────────────────────

    /** 입력 → 엔티티. 정산 계좌·사업자 정보는 세 역할 모두, 메모는 업체 계정 제외, 수수료율은 크로스허브만 (통화·운영방식은 호출부) */
    private static void apply(Partner p, PartnerUpsertRequest in, Caller caller) {
        p.setName(in.name());
        p.setManager(in.managerName());
        p.setPhone(in.phone());
        p.setEmail(in.email());
        p.setBankName(in.bankName());
        p.setBankAccountNumber(in.bankAccountNumber());
        p.setBankAccountHolder(in.bankAccountHolder());
        p.setBankCode(in.bankCode());
        p.setBusinessRegistrationNumber(in.businessRegistrationNumber());
        p.setRepresentativeName(in.representativeName());
        p.setCorporateName(in.corporateName());
        p.setAddress(in.address());
        p.setBusinessLicenseUrl(in.businessLicenseUrl());
        p.setSubManager(in.subManager());
        p.setSubManagerPhone(in.subManagerPhone());
        if (!"partner".equals(caller.role())) p.setMemo(in.memo());
        if (caller.isCrosshub()) p.setCommissionRate(in.commissionRate());
    }

    /** FE actions.ts parse()와 같은 검사 + 빈 문자열 → null. FE를 거치지 않은 호출도 막는다. (사업자등록증 URL 검사는 스토리지 주소를 아는 FE에 둔다) */
    static PartnerUpsertRequest validate(PartnerUpsertRequest r) {
        String name = trim(r.name());
        if (name == null) throw new PartnerException(HttpStatus.BAD_REQUEST, "required");
        if (name.length() > NAME_MAX) throw new PartnerException(HttpStatus.BAD_REQUEST, "nameLength");
        PartnerUpsertRequest n = new PartnerUpsertRequest(
                name, trim(r.proxyAffiliateId()), r.commissionRate(), trim(r.currency()) == null ? "JPY" : trim(r.currency()),
                trim(r.managerName()), trim(r.phone()), trim(r.email()), trim(r.bankName()), trim(r.bankAccountNumber()),
                trim(r.bankAccountHolder()), trim(r.memo()), trim(r.businessRegistrationNumber()), trim(r.representativeName()),
                trim(r.corporateName()), trim(r.address()), trim(r.businessLicenseUrl()), trim(r.subManager()),
                trim(r.subManagerPhone()), trim(r.bankCode()));
        for (String s : new String[] {n.managerName(), n.phone(), n.email(), n.bankName(), n.bankAccountNumber(),
                n.bankAccountHolder(), n.bankCode(), n.businessRegistrationNumber(), n.representativeName(),
                n.corporateName(), n.subManager(), n.subManagerPhone()}) {
            if (s != null && s.length() > TEXT_MAX) throw new PartnerException(HttpStatus.BAD_REQUEST, "tooLong");
        }
        if ((n.memo() != null && n.memo().length() > MEMO_MAX) || (n.address() != null && n.address().length() > MEMO_MAX)) {
            throw new PartnerException(HttpStatus.BAD_REQUEST, "tooLong");
        }
        if (n.email() != null && !EMAIL.matcher(n.email()).matches()) throw new PartnerException(HttpStatus.BAD_REQUEST, "emailFormat");
        if (n.commissionRate() != null && (n.commissionRate() < 0 || n.commissionRate() > 100)) {
            throw new PartnerException(HttpStatus.BAD_REQUEST, "commissionRate");
        }
        if (!CURRENCY.matcher(n.currency()).matches()) throw new PartnerException(HttpStatus.BAD_REQUEST, "currency");
        return n;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 수수료율 < PG 수수료율 + 연맹 몫이면 크로스허브 몫이 마이너스라 막는다 (FE commissionBelowShares) */
    private boolean commissionBelowShares(Integer rate, String currency, String proxyAffiliateId) {
        if (rate == null) return false;
        double pgFee = pgSettings.findById(currency).map(PgSetting::getFeeRate).map(v -> v == null ? 0.0 : v).orElse(0.0);
        int share = proxyAffiliateId == null ? 0
                : affiliates.findById(proxyAffiliateId).map(Affiliate::getShareRate).map(v -> v == null ? 0 : v).orElse(0);
        return rate < pgFee + share;
    }

    private static Map<String, String> bankFields(Partner p) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bankName", p.getBankName());
        m.put("bankAccountNumber", p.getBankAccountNumber());
        m.put("bankAccountHolder", p.getBankAccountHolder());
        m.put("bankCode", p.getBankCode());
        return m;
    }

    private static Map<String, String> infoFields(Partner p) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("businessRegistrationNumber", p.getBusinessRegistrationNumber());
        m.put("corporateName", p.getCorporateName());
        m.put("representativeName", p.getRepresentativeName());
        m.put("address", p.getAddress());
        m.put("businessLicenseUrl", p.getBusinessLicenseUrl());
        return m;
    }

    /** 바뀐 칸만 [이전, 새]. mask인 칸(계좌번호)은 뒤 4자리만 (FE maskAccount) */
    static Map<String, Object[]> diff(Map<String, String> before, Map<String, String> after, Function<String, Boolean> mask) {
        Map<String, Object[]> d = new LinkedHashMap<>();
        for (String k : before.keySet()) {
            String a = before.get(k), b = after.get(k);
            if (!Objects.equals(a, b)) d.put(k, mask.apply(k) ? new Object[] {maskAccount(a), maskAccount(b)} : new Object[] {a, b});
        }
        return d;
    }

    static String maskAccount(String v) {
        if (v == null || v.isEmpty()) return null;
        return "****" + v.substring(Math.max(0, v.length() - 4));
    }

    private void audit(Caller caller, String action, Partner p, Map<String, Object[]> detail) {
        try {
            auditLogs.save(AuditLog.of(caller.userId(), action, p.getId(), p.getId(), p.getName(), objectMapper.writeValueAsString(detail)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private PartnerResponse toResponse(Partner p, String proxyAffiliateId) {
        // 방금 바꾼 proxyAffiliateId는 지연 조인(proxyAffiliate)에 반영이 안 돼 있을 수 있어 이름을 직접 찾는다
        String affName = proxyAffiliateId == null ? null : affiliates.findById(proxyAffiliateId).map(Affiliate::getName).orElse(null);
        return PartnerResponse.from(p, affName, products.countByPartnerIdAndHiddenByPartnerOffTrue(p.getId()));
    }

    private static PartnerException notFound() {
        return new PartnerException(HttpStatus.NOT_FOUND, "notFound");
    }
}
