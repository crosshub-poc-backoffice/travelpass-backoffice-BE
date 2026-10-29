package com.crosshub.backoffice.product;

import com.crosshub.backoffice.account.AuditLog;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.notification.Notifier;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.product.change.ProductChangeRequest;
import com.crosshub.backoffice.product.change.ProductChangeRequestRepository;
import com.crosshub.backoffice.product.translation.ProductTranslation;
import com.crosshub.backoffice.product.translation.ProductTranslationReadService;
import com.crosshub.backoffice.product.translation.ProductTranslationRepository;
import com.crosshub.backoffice.product.translation.ProductTranslationService;
import com.crosshub.backoffice.product.translation.ProductTexts;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.ChangeRequestRow;
import com.crosshub.backoffice.product.change.ChangeRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import com.crosshub.backoffice.product.dto.ProductDetailResponse;
import com.crosshub.backoffice.product.dto.ProductStatusRequest;
import com.crosshub.backoffice.product.dto.ProductSummaryResponse;
import com.crosshub.backoffice.product.dto.ProductUpsertRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 상품 쓰기. 저장 뒤 자동 번역·AI 검수(ProductBackgroundJobs), 담당자 알림(Notifier), 크로스허브 직접 처리 때
 * 대기중 변경요청 자동 반려까지 여기서 한다 — 예전엔 FE 가 BE 응답을 받은 뒤 DB 에 직접 썼다.
 */
@Service
public class ProductService {

    /** 크로스허브가 직접 처리해서 대기중 변경요청이 자동 반려될 때 남기는 사유 (FE AUTO_REJECT_REASON 과 같은 문자열) */
    public static final String AUTO_REJECT_REASON = "크로스허브가 상품을 직접 처리하여 자동으로 반려되었습니다.";

    /** 호출 경로. DIRECT = 화면에서 바로, VIA_REQUEST = 변경요청 승인 반영(자동 반려·직접처리 알림을 하지 않는다) */
    public enum Origin { DIRECT, VIA_REQUEST }

    private final ProductRepository repository;
    private final PartnerRepository partners;
    private final AuditLogRepository auditLogs;
    private final ProductChangeRequestRepository changeRequests;
    private final ProductTranslationService translations;
    private final Notifier notifier;
    private final ProductBackgroundJobs jobs;
    private final ProductTranslationReadService translationReads;
    private final ProductTranslationRepository translationRows;
    private final ChangeRequestService changeRequestReads;
    private final ObjectMapper om;

    public ProductService(ProductRepository repository, PartnerRepository partners, AuditLogRepository auditLogs,
                          ProductChangeRequestRepository changeRequests, ProductTranslationService translations,
                          Notifier notifier, ProductBackgroundJobs jobs, ProductTranslationReadService translationReads,
                          ProductTranslationRepository translationRows, @org.springframework.context.annotation.Lazy ChangeRequestService changeRequestReads,
                          ObjectMapper om) {
        this.repository = repository;
        this.partners = partners;
        this.auditLogs = auditLogs;
        this.changeRequests = changeRequests;
        this.translations = translations;
        this.notifier = notifier;
        this.jobs = jobs;
        this.translationReads = translationReads;
        this.translationRows = translationRows;
        this.changeRequestReads = changeRequestReads;
        this.om = om;
    }

    /**
     * 목록. crosshub는 전체(선택적 status), affiliate는 대행 업체, partner는 자기 업체 것만.
     * partnerName은 Product.partner(읽기전용 조인)의 지연 로딩이라, open-in-view=false에서는
     * 트랜잭션이 열려 있는 이 메서드 안에서 DTO로 매핑해야 한다 (지연 로딩이라).
     */
    @Transactional(readOnly = true)
    public List<ProductSummaryResponse> list(Caller caller, String status) {
        return list(caller, status, null, null);
    }

    /**
     * 목록. keyword 는 상품명·업체명·어느 언어든 번역된 상품명에 들어 있으면. locale 을 주면 그 언어 번역 상품명(nameLocalized)도.
     * 번역 상태·최근 변경요청(대기중·반려됨)을 행마다 같이 준다 — FE 목록이 DB 를 안 읽어도 되게.
     */
    @Transactional(readOnly = true)
    public List<ProductSummaryResponse> list(Caller caller, String status, String keyword, String locale) {
        if (status != null && status.isBlank()) status = null;
        // 모르는 값이 쿼리로 가면 DB enum 변환에서 500이 나니 먼저 거른다
        if (status != null && !Product.STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidStatus");
        }
        List<Product> rows;
        if (caller.isCrosshub()) {
            rows = status != null ? repository.findByStatus(status) : repository.findAll();
        } else {
            List<String> pids = caller.partnerIdsForQuery();
            rows = status != null
                    ? repository.findByPartnerIdInAndStatus(pids, status)
                    : repository.findByPartnerIdIn(pids);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            java.util.Set<String> translated = new java.util.HashSet<>(translationRows.findProductIdsByTranslatedName("%" + kw + "%"));
            rows = rows.stream().filter(p -> p.getName().toLowerCase().contains(kw)
                    || (p.getPartner() != null && p.getPartner().getName() != null && p.getPartner().getName().toLowerCase().contains(kw))
                    || translated.contains(p.getId())).toList();
        }
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.stream().map(Product::getId).toList();
        Map<String, List<ProductTranslation>> byProduct = new java.util.HashMap<>();
        for (ProductTranslation t : translationRows.findByProductIdIn(ids)) byProduct.computeIfAbsent(t.getProductId(), k -> new java.util.ArrayList<>()).add(t);
        Map<String, ChangeRequestRow> latest = changeRequestReads.latestByProduct(caller, ids, false);
        boolean localize = locale != null && !locale.isBlank();
        List<ProductSummaryResponse> out = new java.util.ArrayList<>();
        for (Product p : rows) {
            List<ProductTranslation> trs = byProduct.getOrDefault(p.getId(), List.of());
            String nameLocalized = p.getName();
            if (localize) {
                for (ProductTranslation t : trs) {
                    if (t.getLocale().equals(locale)) {
                        String n = ProductTexts.asMap(t.getTexts(), om).get("name");
                        if (n != null && !n.trim().isEmpty()) nameLocalized = n.trim();
                    }
                }
            }
            out.add(ProductSummaryResponse.from(p, translationReads.summary(p, trs), nameLocalized, latest.get(p.getId())));
        }
        return out;
    }

    /** 날짜별 판매 여부·정원 (open/max). FE 빠른 보기 모달의 캘린더용 */
    public record DateCapacity(boolean open, Integer max) {}

    /** 그 달 날짜마다 {open, max}. 일정이 미설정("{}")이면 전부 무제한. 하루 단위로만 본다. 범위 밖 상품이면 404 */
    @Transactional(readOnly = true)
    public Map<String, DateCapacity> capacity(Caller caller, String id, String month) {
        Product p = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) throw notFound(id);
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidMonth");
        }
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        boolean configured = p.getSchedule() != null && p.getSchedule().contains("\"base\"");
        Map<String, DateCapacity> out = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!configured) {
                out.put(d.toString(), new DateCapacity(true, null));
                continue;
            }
            SaleSchedule.Day day = SaleSchedule.resolve(p.getSchedule(), d, om);
            out.put(d.toString(), day == null ? new DateCapacity(true, null) : new DateCapacity(day.open(), day.maxPeople()));
        }
        return out;
    }

    /**
     * 상세. 소유 업체가 아니면 404로 숨긴다(존재 노출 방지).
     * partnerName은 Product.partner(읽기전용 조인)의 지연 로딩이라 트랜잭션 안에서 DTO로 매핑한다
     * (컨트롤러에서 매핑하면 open-in-view=false에서 LazyInitializationException — list()와 동일 이유).
     */
    @Transactional(readOnly = true)
    public ProductDetailResponse get(Caller caller, String id) {
        Product p = repository.findById(id)
                .orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) {
            throw notFound(id);
        }
        return ProductDetailResponse.from(p);
    }

    /**
     * 생성. partner는 본인 업체로 강제, crosshub·affiliate는 partnerId를 지정해야 한다
     * (affiliate는 대행 업체만). draft=true면 임시저장(심사 요청 전), 아니면 바로 심사중.
     * 저장 뒤 번역 자동생성·AI 검수는 커밋 후 백그라운드로 돈다 (ProductBackgroundJobs).
     */
    @Transactional
    public ProductDetailResponse create(Caller caller, ProductUpsertRequest req, boolean draft) {
        String partnerId = "partner".equals(caller.role()) ? requirePartner(caller) : req.partnerId();
        if (partnerId == null || partnerId.isBlank()) {
            throw badRequest("partnerId가 필요합니다.");
        }
        if (!caller.canAccessPartner(partnerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 업체의 상품을 등록할 권한이 없습니다.");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw badRequest("name이 필요합니다.");
        }
        if (req.priceTable() == null) {
            throw badRequest("priceTable이 필요합니다.");
        }
        // 임시저장이어도 비활성 업체면 막는다 (FE lib/server/products/service.ts createProduct와 동일 규칙)
        Partner partner = partners.findById(partnerId).orElse(null);
        if (partner == null || !partner.isActive()) {
            throw new ProductException(HttpStatus.CONFLICT, "partnerInactive");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Product p = new Product();
        p.setId(newId());
        p.setPartnerId(partnerId);
        p.initStatus(draft);   // 임시저장이 아니면 등록 직후는 항상 심사중
        p.setConfirmType(orDefault(req.confirmType(), "instant"));
        p.setCategory(orDefault(req.category(), "other"));
        p.setName(req.name());
        p.setImages(req.images() == null ? new String[0] : ProductImages.keys(req.images()));
        p.setDescription(orDefault(req.description(), ""));
        p.setSourceLocale(req.sourceLocale());
        p.setSchedule(json(req.schedule(), "{}"));
        p.setSaleStart(req.saleStart());
        p.setSaleEnd(req.saleEnd());
        p.setDeadlineDays(req.deadlineDays() == null ? 1 : req.deadlineDays());
        p.setDeadlineTime(orDefault(req.deadlineTime(), "17:00"));
        p.setCancelRules(json(req.cancelRules(), "[]"));
        p.setPriceFrom(req.priceFrom());
        p.setPriceTable(json(ProductImages.priceTableToKeys(req.priceTable()), "null"));
        p.setBlocks(json(ProductImages.blocksToKeys(req.blocks()), "[]"));
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        repository.save(p);
        auditLogs.save(AuditLog.of(caller.userId(), "product_create", partnerId, p.getId(), p.getName()));
        // 다른 언어 번역·검수는 응답을 보낸 뒤에 (번역 API 가 느려도 저장은 바로 끝나게). 임시저장은 둘 다 안 한다
        if (!draft) {
            jobs.translate(p.getId());
            jobs.inspect(p.getId());
        }
        // 새로 만든 엔티티는 partner(지연 조인) 프록시가 비어 있고, 같은 트랜잭션 안에서 findById로
        // "재조회"해도 Hibernate 1차 캐시(영속성 컨텍스트) 때문에 같은 인스턴스가 그대로 돌아와 안 채워진다
        // — 위에서 이미 조회해 둔 Partner로 partnerName을 직접 넘긴다.
        return ProductDetailResponse.from(p, partner.getName());
    }

    /**
     * 수정. 소유 업체만. null이 아닌 필드만 덮어쓴다. submit=true면 임시저장 상품을 심사중으로 올린다.
     * (상태 전환 자체(승인·숨김 등)는 별도 엔드포인트에서 다룰 예정 — 여기선 draft→review만)
     * 업체는 바꾸지 않는다 — 문의는 상품으로만 업체를 찾아서, 바꾸면 지난 문의가 새 업체로 넘어간다.
     */
    @Transactional
    public ProductDetailResponse update(Caller caller, String id, ProductUpsertRequest req, boolean submit) {
        return update(caller, id, req, submit, Origin.DIRECT);
    }

    @Transactional
    public ProductDetailResponse update(Caller caller, String id, ProductUpsertRequest req, boolean submit, Origin origin) {
        Product p = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) {
            throw notFound(id);
        }
        p.requireDirectEditable(caller.isCrosshub());
        String before = p.getStatus();
        supersedePendingRequest(caller, p, origin);
        if (!isPartnerActive(p.getPartnerId())) {
            throw new ProductException(HttpStatus.CONFLICT, "partnerInactive");
        }
        if (req.name() != null) p.setName(req.name());
        if (req.category() != null) p.setCategory(req.category());
        if (req.confirmType() != null) p.setConfirmType(req.confirmType());
        if (req.description() != null) p.setDescription(req.description());
        if (req.sourceLocale() != null) p.setSourceLocale(req.sourceLocale());
        if (req.images() != null) p.setImages(ProductImages.keys(req.images()));
        if (req.schedule() != null) p.setSchedule(json(req.schedule(), "{}"));
        if (req.cancelRules() != null) p.setCancelRules(json(req.cancelRules(), "[]"));
        if (req.priceTable() != null) p.setPriceTable(json(ProductImages.priceTableToKeys(req.priceTable()), "null"));
        if (req.blocks() != null) p.setBlocks(json(ProductImages.blocksToKeys(req.blocks()), "[]"));
        if (req.saleStart() != null) p.setSaleStart(req.saleStart());
        if (req.saleEnd() != null) p.setSaleEnd(req.saleEnd());
        if (req.deadlineDays() != null) p.setDeadlineDays(req.deadlineDays());
        if (req.deadlineTime() != null) p.setDeadlineTime(req.deadlineTime());
        if (req.priceFrom() != null) p.setPriceFrom(req.priceFrom());
        boolean toReview = p.submitIfDraft(submit);
        p.setUpdatedAt(OffsetDateTime.now());
        repository.save(p);
        auditLogs.save(AuditLog.of(caller.userId(), "product_update", p.getPartnerId(), p.getId(), p.getName()));
        if (toReview) {
            auditLogs.save(AuditLog.of(caller.userId(), "product_status", p.getPartnerId(), p.getId(), p.getName(),
                    statusDetail("draft", "review")));
        }
        // 바뀐 칸만 다시 번역 (변경요청 승인으로 반영될 때도 여기를 지난다). 검수는 심사 대상이 되는 상품만
        jobs.translate(p.getId());
        if (!"draft".equals(before) || toReview) jobs.inspect(p.getId());
        notifyDirectChange(caller, p, before, origin, "edit", null);
        // p는 findById로 읽어온 관리 엔티티라 partner(지연 조인) 프록시가 이미 걸려 있다 — 재조회 불필요.
        return ProductDetailResponse.from(p);
    }

    /**
     * 상태 전환(승인·반려·노출·숨김). 심사중(review) 상품은 크로스허브만 바꾼다(승인·반려).
     * 노출중 ↔ 숨김은 상품을 다룰 수 있는 누구나. 심사중 → 숨김 → 노출중으로 돌아서 승인을 건너뛰지
     * 못하게, 심사중에서 나가는 것 자체를 승인으로 본다 (FE setProductStatus와 동일 규칙).
     *
     * 번역이 다 채워지기 전엔 노출하지 못한다(translationMissing) — TP 앱의 외국인 고객에게 원문만 나가지 않게.
     * 심사(review)에서 나가면(승인·반려) 소유측(업체+대행연맹)에게 알림을 만든다.
     */
    @Transactional
    public ProductDetailResponse setStatus(Caller caller, String id, ProductStatusRequest req) {
        return setStatus(caller, id, req, Origin.DIRECT);
    }

    @Transactional
    public ProductDetailResponse setStatus(Caller caller, String id, ProductStatusRequest req, Origin origin) {
        Product p = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) {
            throw notFound(id);
        }
        String from = p.getStatus();
        String to = req.status();
        if (from.equals(to)) return ProductDetailResponse.from(p);
        supersedePendingRequest(caller, p, origin);
        // 노출중으로 갈 때만 업체(활성·수수료율)·번역 완료를 본다. 규칙 자체는 Product.changeStatus 에 있다
        Partner partner = "live".equals(to) ? partners.findById(p.getPartnerId()).orElse(null) : null;
        if (!p.changeStatus(caller.isCrosshub(), to, req.reason(), partner, () -> translations.complete(p))) return ProductDetailResponse.from(p);
        repository.save(p);
        auditLogs.save(AuditLog.of(caller.userId(), "product_status", p.getPartnerId(), p.getId(), p.getName(),
                statusDetail(from, to)));
        if ("review".equals(from) && ("live".equals(to) || "hidden".equals(to))) {
            // sourceId 에 상태를 같이 넣는다 — productId 만 쓰면 이후 live→hidden 같은 다음 전환이 같은
            // (userId, type, sourceId) 조합이 되어 유니크 제약에 걸려 조용히 버려진다.
            notifier.notify(notifier.partnerStakeholders(p.getPartnerId(), false, true, true), "product_status_changed",
                    p.getId() + ":" + to, Map.of("productId", p.getId(), "productName", p.getName(), "status", to));
        }
        notifyDirectChange(caller, p, from, origin, "hide", to);
        return ProductDetailResponse.from(p);
    }

    /** 삭제. 문의가 달린 상품은 FK 제약에 걸려 못 지운다(hasInquiries — 숨김으로 바꾸게 한다). */
    @Transactional
    public void delete(Caller caller, String id) {
        delete(caller, id, Origin.DIRECT);
    }

    @Transactional
    public void delete(Caller caller, String id, Origin origin) {
        Product p = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) {
            throw notFound(id);
        }
        p.requireDirectEditable(caller.isCrosshub());
        String before = p.getStatus();
        // 삭제되면 요청 행도 cascade 로 사라진다 — 알림만 남기려고 지우기 전에 반려·알림을 먼저 한다
        supersedePendingRequest(caller, p, origin);
        try {
            repository.delete(p);
            repository.flush(); // FK 위반을 이 트랜잭션 안에서(여기서) 바로 받으려고
        } catch (DataIntegrityViolationException e) {
            throw new ProductException(HttpStatus.CONFLICT, "hasInquiries");
        }
        auditLogs.save(AuditLog.of(caller.userId(), "product_delete", p.getPartnerId(), id, p.getName()));
        notifyDirectChange(caller, p, before, origin, "delete", null);
    }

    /**
     * 크로스허브가 심사를 통과한(live/hidden) 상품을 직접 수정·삭제·전환하기 직전: 그 상품에 열려 있는 변경요청이 있으면
     * 자동으로 반려해 "요청과 직접처리가 동시에 떠 있는" 상태를 막는다. 요청한 쪽에는 반려 알림이 간다.
     */
    private void supersedePendingRequest(Caller caller, Product p, Origin origin) {
        if (origin != Origin.DIRECT || !caller.isCrosshub() || !isPublished(p.getStatus())) return;
        Optional<ProductChangeRequest> pending = changeRequests.findFirstByProductIdAndStatus(p.getId(), "pending");
        if (pending.isEmpty()) return;
        ProductChangeRequest r = pending.get();
        // 벌크 UPDATE(markRejected)는 clearAutomatically 로 영속성 컨텍스트를 비워 p 가 떨어져 나간다(partner 지연 조인이 깨짐) —
        // 엔티티 메서드로 바꾼다. 자동 반려는 크로스허브 한 명의 직접 처리 안에서 일어나 경합 보호가 필요 없다
        r.reject(caller.userId(), AUTO_REJECT_REASON);
        changeRequests.save(r);
        notifier.notify(notifier.partnerStakeholders(p.getPartnerId(), false, true, true), "product_change_resolved",
                r.getId() + ":rejected", resolvedPayload(r, p, "rejected", AUTO_REJECT_REASON));
    }

    /** 크로스허브가 심사를 통과한 상품을 직접 수정/삭제/전환했을 때 소유측(업체+대행연맹)에게 알림 */
    private void notifyDirectChange(Caller caller, Product p, String statusBefore, Origin origin, String changeType, String targetStatus) {
        if (origin != Origin.DIRECT || !caller.isCrosshub() || !isPublished(statusBefore)) return;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("productId", p.getId());
        payload.put("productName", p.getName());
        payload.put("changeType", changeType);
        if (targetStatus != null) payload.put("targetStatus", targetStatus);
        notifier.notify(notifier.partnerStakeholders(p.getPartnerId(), false, true, true), "product_direct_change",
                p.getId() + ":" + changeType + ":" + System.currentTimeMillis(), payload);
    }

    /** 변경요청 처리 결과 알림 payload (ChangeRequestService 도 쓴다). targetStatus 는 live/hidden 일 때만 */
    public static Map<String, Object> resolvedPayload(ProductChangeRequest r, Product p, String result, String reason) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("requestId", r.getId());
        payload.put("productId", p.getId());
        payload.put("productName", p.getName());
        payload.put("changeType", r.getType());
        if ("live".equals(r.getTargetStatus()) || "hidden".equals(r.getTargetStatus())) payload.put("targetStatus", r.getTargetStatus());
        payload.put("result", result);
        if ("rejected".equals(result) && reason != null) payload.put("reason", reason);
        return payload;
    }

    private static boolean isPublished(String status) {
        return "live".equals(status) || "hidden".equals(status);
    }


    /** AuditLog.detail — FE와 같은 모양 {"status":[이전, 새]}. 값은 위 STATUSES 중 하나라 이스케이프 불필요 */
    private static String statusDetail(String from, String to) {
        return "{\"status\":[\"" + from + "\",\"" + to + "\"]}";
    }


    private boolean isPartnerActive(String partnerId) {
        return partners.findById(partnerId).map(Partner::isActive).orElse(false);
    }

    private static String requirePartner(Caller caller) {
        if (caller.partnerId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "업체 소속 계정이 아닙니다.");
        }
        return caller.partnerId();
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    /** JsonNode → 저장용 JSON 문자열. null이면 fallback. */
    private static String json(JsonNode node, String fallback) {
        return node == null || node.isNull() ? fallback : node.toString();
    }

    /** 새 상품 id. Prisma는 cuid를 쓰지만 BE 생성분은 UUID(하이픈 제거)로 둔다 — 형식만 다르고 기능은 동일. */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다: " + id);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
