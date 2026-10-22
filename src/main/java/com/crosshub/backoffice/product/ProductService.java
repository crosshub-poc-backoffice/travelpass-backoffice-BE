package com.crosshub.backoffice.product;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.product.dto.ProductDetailResponse;
import com.crosshub.backoffice.product.dto.ProductSummaryResponse;
import com.crosshub.backoffice.product.dto.ProductUpsertRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    /**
     * 목록. crosshub는 전체(선택적 status), partner는 자기 업체 것만.
     * partnerName은 Product.partner(읽기전용 조인)의 지연 로딩이라, open-in-view=false에서는
     * 트랜잭션이 열려 있는 이 메서드 안에서 DTO로 매핑해야 한다 (BookingService.list와 동일 패턴).
     */
    @Transactional(readOnly = true)
    public List<ProductSummaryResponse> list(Caller caller, String status) {
        List<Product> rows;
        if (caller.isCrosshub()) {
            rows = status != null ? repository.findByStatus(status) : repository.findAll();
        } else {
            String pid = requirePartner(caller);
            rows = status != null
                    ? repository.findByPartnerIdAndStatus(pid, status)
                    : repository.findByPartnerId(pid);
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

    /** 생성. partner는 본인 업체로 강제, crosshub는 partnerId를 지정해야 한다. 상태는 항상 review로 시작. */
    @Transactional
    public ProductDetailResponse create(Caller caller, ProductUpsertRequest req) {
        String partnerId = caller.isCrosshub() ? req.partnerId() : requirePartner(caller);
        if (partnerId == null || partnerId.isBlank()) {
            throw badRequest("partnerId가 필요합니다.");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw badRequest("name이 필요합니다.");
        }
        if (req.priceTable() == null) {
            throw badRequest("priceTable이 필요합니다.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Product p = new Product();
        p.setId(newId());
        p.setPartnerId(partnerId);
        p.setStatus("review");                 // 등록 직후는 항상 심사중
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
        // 새로 만든 엔티티는 partner(지연 조인) 프록시가 안 걸려 있어 — save 직후 그대로 매핑하면
        // partnerName이 null로 나간다. 같은 트랜잭션 안에서 다시 읽어와야 조인이 걸린다.
        Product saved = repository.findById(p.getId()).orElseThrow(() -> notFound(p.getId()));
        return ProductDetailResponse.from(saved);
    }

    /** 수정. 소유 업체만. null이 아닌 필드만 덮어쓴다. (status 전환은 별도 엔드포인트에서 다룰 예정) */
    @Transactional
    public ProductDetailResponse update(Caller caller, String id, ProductUpsertRequest req) {
        Product p = repository.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getPartnerId())) {
            throw notFound(id);
        }
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
        p.setUpdatedAt(OffsetDateTime.now());
        repository.save(p);
        // p는 findById로 읽어온 관리 엔티티라 partner(지연 조인) 프록시가 이미 걸려 있다 — 재조회 불필요.
        return ProductDetailResponse.from(p);
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
