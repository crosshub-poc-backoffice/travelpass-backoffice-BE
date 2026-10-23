package com.crosshub.backoffice.product;

import com.crosshub.backoffice.account.AuditLog;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.product.dto.ProductDetailResponse;
import com.crosshub.backoffice.product.dto.ProductStatusRequest;
import com.crosshub.backoffice.product.dto.ProductSummaryResponse;
import com.crosshub.backoffice.product.dto.ProductUpsertRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

    private final ProductRepository repository;
    private final PartnerRepository partners;
    private final AuditLogRepository auditLogs;

    public ProductService(ProductRepository repository, PartnerRepository partners, AuditLogRepository auditLogs) {
        this.repository = repository;
        this.partners = partners;
        this.auditLogs = auditLogs;
    }

    /**
     * 목록. crosshub는 전체(선택적 status), affiliate는 대행 업체, partner는 자기 업체 것만.
     * partnerName은 Product.partner(읽기전용 조인)의 지연 로딩이라, open-in-view=false에서는
     * 트랜잭션이 열려 있는 이 메서드 안에서 DTO로 매핑해야 한다 (BookingService.list와 동일 패턴).
     */
    @Transactional(readOnly = true)
    public List<ProductSummaryResponse> list(Caller caller, String status) {
        List<Product> rows;
        if (caller.isCrosshub()) {
            rows = status != null ? repository.findByStatus(status) : repository.findAll();
        } else {
            List<String> pids = caller.partnerIdsForQuery();
            rows = status != null
                    ? repository.findByPartnerIdInAndStatus(pids, status)
                    : repository.findByPartnerIdIn(pids);
        }
        return rows.stream().map(ProductSummaryResponse::from).toList();
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
     * 번역 자동생성·AI 검수 트리거는 FE가 이 응답의 id로 이어서 한다(그 서브시스템은 아직 BE에 없음).
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
        p.setStatus(draft ? "draft" : "review");   // 임시저장이 아니면 등록 직후는 항상 심사중
        p.setHiddenByPartnerOff(false);
        p.setConfirmType(orDefault(req.confirmType(), "instant"));
        p.setCategory(orDefault(req.category(), "other"));
        p.setName(req.name());
        p.setImages(req.images() == null ? new String[0] : req.images().toArray(new String[0]));
        p.setDescription(orDefault(req.description(), ""));
        p.setSourceLocale(req.sourceLocale());
        p.setSchedule(json(req.schedule(), "{}"));
        p.setSaleStart(req.saleStart());
        p.setSaleEnd(req.saleEnd());
        p.setDeadlineDays(req.deadlineDays() == null ? 1 : req.deadlineDays());
        p.setDeadlineTime(orDefault(req.deadlineTime(), "17:00"));
        p.setCancelRules(json(req.cancelRules(), "[]"));
        p.setPriceFrom(req.priceFrom());
        p.setPriceTable(json(req.priceTable(), "null"));
        p.setBlocks(json(req.blocks(), "[]"));
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        repository.save(p);
        auditLogs.save(AuditLog.of(caller.userId(), "product_create", partnerId, p.getId(), p.getName()));
        // 새로 만든 엔티티는 partner(지연 조인) 프록시가 비어 있고, 같은 트랜잭션 안에서 findById로
        // "재조회"해도 Hibernate 1차 캐시(영속성 컨텍스트) 때문에 같은 인스턴스가 그대로 돌아와 안 채워진다
        // — 위에서 이미 조회해 둔 Partner로 partnerName을 직접 넘긴다.
        return ProductDetailResponse.from(p, partner.getName());
    }

    /**
     * 수정. 소유 업체만. null이 아닌 필드만 덮어쓴다. submit=true면 임시저장 상품을 심사중으로 올린다.
     * (상태 전환 자체(승인·숨김 등)는 별도 엔드포인트에서 다룰 예정 — 여기선 draft→review만)
     * 업체는 바꾸지 않는다 — 예약은 상품으로만 업체를 찾아서, 바꾸면 지난 예약·정산이 새 업체로 넘어간다.
     */
    @Transactional
    public ProductDetailResponse update(Caller caller, String id, ProductUpsertRequest req, boolean submit) {
        Product p = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) {
            throw notFound(id);
        }
        if (!isPartnerActive(p.getPartnerId())) {
            throw new ProductException(HttpStatus.CONFLICT, "partnerInactive");
        }
        boolean toReview = submit && "draft".equals(p.getStatus());
        if (req.name() != null) p.setName(req.name());
        if (req.category() != null) p.setCategory(req.category());
        if (req.confirmType() != null) p.setConfirmType(req.confirmType());
        if (req.description() != null) p.setDescription(req.description());
        if (req.sourceLocale() != null) p.setSourceLocale(req.sourceLocale());
        if (req.images() != null) p.setImages(req.images().toArray(new String[0]));
        if (req.schedule() != null) p.setSchedule(json(req.schedule(), "{}"));
        if (req.cancelRules() != null) p.setCancelRules(json(req.cancelRules(), "[]"));
        if (req.priceTable() != null) p.setPriceTable(json(req.priceTable(), "null"));
        if (req.blocks() != null) p.setBlocks(json(req.blocks(), "[]"));
        if (req.saleStart() != null) p.setSaleStart(req.saleStart());
        if (req.saleEnd() != null) p.setSaleEnd(req.saleEnd());
        if (req.deadlineDays() != null) p.setDeadlineDays(req.deadlineDays());
        if (req.deadlineTime() != null) p.setDeadlineTime(req.deadlineTime());
        if (req.priceFrom() != null) p.setPriceFrom(req.priceFrom());
        if (toReview) p.setStatus("review");
        p.setUpdatedAt(OffsetDateTime.now());
        repository.save(p);
        auditLogs.save(AuditLog.of(caller.userId(), "product_update", p.getPartnerId(), p.getId(), p.getName()));
        if (toReview) {
            auditLogs.save(AuditLog.of(caller.userId(), "product_status", p.getPartnerId(), p.getId(), p.getName(),
                    statusDetail("draft", "review")));
        }
        // p는 findById로 읽어온 관리 엔티티라 partner(지연 조인) 프록시가 이미 걸려 있다 — 재조회 불필요.
        return ProductDetailResponse.from(p);
    }

    /**
     * 상태 전환(승인·반려·노출·숨김). 심사중(review) 상품은 크로스허브만 바꾼다(승인·반려).
     * 노출중 ↔ 숨김은 상품을 다룰 수 있는 누구나. 심사중 → 숨김 → 노출중으로 돌아서 승인을 건너뛰지
     * 못하게, 심사중에서 나가는 것 자체를 승인으로 본다 (FE setProductStatus와 동일 규칙).
     *
     * ⚠️ translationsComplete(번역 전부 채워졌는지) 체크는 여기 없다 — 번역(ProductTranslation)은
     * 아직 BE에 없는 서브시스템이라 FE가 호출 전에 미리 확인하고서만 이 엔드포인트를 부른다.
     * 알림 생성(review→live/hidden 전환 시 담당자에게)도 FE가 이 응답을 받은 뒤 이어서 한다.
     */
    @Transactional
    public ProductDetailResponse setStatus(Caller caller, String id, ProductStatusRequest req) {
        Product p = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) {
            throw notFound(id);
        }
        String from = p.getStatus();
        String to = req.status();
        // 모르는 값이면 enum 캐스팅에서 500이 나니 먼저 막는다 (FE는 타입으로 막혀 있었음)
        if (to == null || !STATUSES.contains(to)) throw notFound(id);
        if ("draft".equals(from) || "draft".equals(to)) throw notFound(id);
        if ("review".equals(from) && !"review".equals(to) && !caller.isCrosshub()) {
            throw new ProductException(HttpStatus.FORBIDDEN, "approveCrosshubOnly");
        }
        if ("review".equals(to) && !"review".equals(from)) throw notFound(id); // 심사중으로 되돌리기는 없음
        boolean needsReason = "review".equals(from) && "hidden".equals(to);
        if (needsReason && (req.reason() == null || req.reason().isBlank())) {
            throw new ProductException(HttpStatus.BAD_REQUEST, "required");
        }
        if (from.equals(to)) return ProductDetailResponse.from(p);

        if ("live".equals(to)) {
            if (!isPartnerActive(p.getPartnerId())) throw new ProductException(HttpStatus.CONFLICT, "partnerInactive");
            Partner partner = partners.findById(p.getPartnerId()).orElse(null);
            if (partner == null || partner.getCommissionRate() == null) {
                throw new ProductException(HttpStatus.CONFLICT, "commissionMissing");
            }
        }
        // 반려 사유: review→hidden일 때만 저장, live(재승인)가 되면 과거 사유를 지운다. 그 외는 그대로 둠
        if (needsReason) {
            p.setReviewRejectReason(req.reason().trim());
        } else if ("live".equals(to)) {
            p.setReviewRejectReason(null);
        }
        p.setStatus(to);
        p.setUpdatedAt(OffsetDateTime.now());
        repository.save(p);
        auditLogs.save(AuditLog.of(caller.userId(), "product_status", p.getPartnerId(), p.getId(), p.getName(),
                statusDetail(from, to)));
        return ProductDetailResponse.from(p);
    }

    /** 삭제. 문의·예약이 달린 상품은 FK 제약에 걸려 못 지운다(hasBookings — 숨김으로 바꾸게 한다). */
    @Transactional
    public void delete(Caller caller, String id) {
        Product p = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) {
            throw notFound(id);
        }
        try {
            repository.delete(p);
            repository.flush(); // FK 위반을 이 트랜잭션 안에서(여기서) 바로 받으려고
        } catch (DataIntegrityViolationException e) {
            throw new ProductException(HttpStatus.CONFLICT, "hasBookings");
        }
        auditLogs.save(AuditLog.of(caller.userId(), "product_delete", p.getPartnerId(), id, p.getName()));
    }

    private static final java.util.Set<String> STATUSES = java.util.Set.of("draft", "review", "live", "hidden");

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
