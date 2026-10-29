package com.crosshub.backoffice.product.change;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.UserAccount;
import com.crosshub.backoffice.account.UserAccountRepository;
import com.crosshub.backoffice.notification.Notifier;
import com.crosshub.backoffice.product.PriceFrom;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductException;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.ProductService;
import com.crosshub.backoffice.product.ProductService.Origin;
import com.crosshub.backoffice.product.SaleRange;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.ChangeRequestRow;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.ChangeRequestView;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.CreateRequest;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.PendingPage;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.PendingRow;
import com.crosshub.backoffice.product.dto.ProductStatusRequest;
import com.crosshub.backoffice.product.dto.ProductUpsertRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 변경요청. FE lib/server/products/change-requests.ts 를 옮긴 것.
 *  - 생성: 상품을 다룰 수 있는 사람(범위 안). live/hidden 상품만, 대기중 요청이 이미 있으면 duplicatePending. 크로스허브에게 알림.
 *  - 승인: 크로스허브만. 승인 표시와 실제 반영(수정·삭제·상태 전환 — ProductService)을 한 트랜잭션에서 한다.
 *    반영이 실패하면(hasInquiries·partnerInactive·translationMissing) 전부 되돌아가 요청은 대기중으로 남는다.
 *    예전엔 FE 가 "선점 → 상품 API → 실패 시 reopen" 세 번에 나눠 불렀다.
 *  - 반려: 크로스허브만. 상품은 건드리지 않는다.
 *  승인·반려 뒤 소유측(업체+대행연맹)에게 알림.
 */
@Service
public class ChangeRequestService {

    private static final Set<String> TYPES = Set.of("edit", "delete", "hide");
    static final int REASON_MAX = 1000;
    /** 수정 요청 본문(상품 전체 입력값 JSON) 상한. 상품 본문·일정·요금표를 넉넉히 담되 비정상적으로 큰 값은 막는다 */
    static final int PROPOSED_MAX = 512 * 1024;
    private static final Set<String> HIDE_TARGETS = Set.of("live", "hidden");

    private final ProductChangeRequestRepository requests;
    private final ProductRepository products;
    private final ProductService productService;
    private final UserAccountRepository users;
    private final Notifier notifier;
    private final ObjectMapper objectMapper;

    public ChangeRequestService(ProductChangeRequestRepository requests, ProductRepository products, ProductService productService,
                                UserAccountRepository users, Notifier notifier, ObjectMapper objectMapper) {
        this.requests = requests;
        this.products = products;
        this.productService = productService;
        this.users = users;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String create(Caller caller, String productId, CreateRequest req) {
        // 상품 행을 잠가서, 같은 상품에 요청이 동시에 들어와도 아래 "대기중 있나?" 확인과 저장이 한 번에 하나씩 돈다
        Product p = products.findByIdForUpdate(productId).orElseThrow(ChangeRequestService::notFound);
        if (!caller.canAccessPartner(p.getPartnerId())) throw notFound();
        // 심사를 통과한 상품만 요청 대상 (임시저장·심사중은 바로 고친다)
        if (!"live".equals(p.getStatus()) && !"hidden".equals(p.getStatus())) throw notFound();
        String type = req.type();
        if (type == null || !TYPES.contains(type)) throw new ProductException(HttpStatus.BAD_REQUEST, "invalidType");
        String target = null;
        String proposed = null;
        if ("hide".equals(type)) {
            if (req.targetStatus() == null || !HIDE_TARGETS.contains(req.targetStatus())) throw new ProductException(HttpStatus.BAD_REQUEST, "invalidType");
            target = req.targetStatus();
        }
        if ("edit".equals(type)) {
            if (req.proposedData() == null || !req.proposedData().isObject()) throw new ProductException(HttpStatus.BAD_REQUEST, "required");
            // 사진은 저장소 안 경로(key)로 바꿔 둔다 — 승인 전에 저장소 주소가 바뀌어도 그대로 쓰인다. 돌려줄 때 URL 로 (ProductImages.toUrls)
            com.fasterxml.jackson.databind.node.ObjectNode input = req.proposedData().deepCopy();
            com.crosshub.backoffice.product.ProductImages.toKeys(input);
            proposed = input.toString();
            if (proposed.length() > PROPOSED_MAX) throw new ProductException(HttpStatus.BAD_REQUEST, "tooLong");
        }
        if (requests.existsByProductIdAndStatus(productId, "pending")) throw new ProductException(HttpStatus.CONFLICT, "duplicatePending");

        ProductChangeRequest r = ProductChangeRequest.pending(productId, type, target, proposed, caller.userId());
        requests.saveAndFlush(r);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", r.getId());
        payload.put("productId", p.getId());
        payload.put("productName", p.getName());
        payload.put("changeType", type);
        if (target != null) payload.put("targetStatus", target);
        payload.put("requesterName", requesterName(caller));
        notifier.notify(notifier.crosshubUserIds(), "product_change_requested", r.getId(), payload);
        return r.getId();
    }

    /**
     * 상품마다 가장 최근 요청 1건(대기중 또는 반려됨). 승인된 요청은 이미 반영이 끝나 더 보여줄 게 없으므로 뺀다.
     * 목록 뱃지(withProposed=false)·빠른 보기 비교(withProposed=true, edit 요청의 proposedData 포함)
     */
    @Transactional(readOnly = true)
    public Map<String, ChangeRequestRow> latestByProduct(Caller caller, java.util.Collection<String> productIds, boolean withProposed) {
        Map<String, ChangeRequestRow> out = new LinkedHashMap<>();
        if (productIds.isEmpty()) return out;
        Map<String, Product> scoped = new LinkedHashMap<>();
        for (Product p : products.findAllById(productIds)) if (caller.canAccessPartner(p.getPartnerId())) scoped.put(p.getId(), p);
        Map<String, String> names = new java.util.HashMap<>();
        for (ProductChangeRequest r : requests.findByProductIdInAndStatusInOrderByCreatedAtDesc(scoped.keySet(), Set.of("pending", "rejected"))) {
            if (out.containsKey(r.getProductId())) continue;
            String requester = names.computeIfAbsent(r.getRequestedById(), id -> users.findById(id).map(UserAccount::getName).orElse(""));
            JsonNode proposed = null;
            if (withProposed && "edit".equals(r.getType()) && r.getProposedData() != null) {
                try { proposed = com.crosshub.backoffice.product.ProductImages.toUrls(objectMapper.readTree(r.getProposedData())); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
            }
            out.put(r.getProductId(), new ChangeRequestRow(r.getId(), r.getProductId(), r.getType(), r.getTargetStatus(), requester,
                    r.getRejectReason(), r.getStatus(), r.getCreatedAt(), proposed));
        }
        return out;
    }

    /** 대시보드: 범위 안 대기중 요청, 오래 기다린 순 상위 N건 + 전체 건수 */
    @Transactional(readOnly = true)
    public PendingPage pending(Caller caller, int limit, String partnerId) {
        java.util.List<String> pids = caller.narrowedPartnerIds(partnerId);
        var page = requests.findPending(pids == null, pids == null ? java.util.List.of("_none_") : pids, org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 50))));
        java.util.List<PendingRow> rows = new java.util.ArrayList<>();
        for (ProductChangeRequest r : page.getContent()) {
            Product p = products.findById(r.getProductId()).orElse(null);
            rows.add(new PendingRow(r.getId(), r.getProductId(), p == null ? null : p.getName(),
                    p == null || p.getPartner() == null ? null : p.getPartner().getName(), r.getType(), r.getTargetStatus(), r.getCreatedAt()));
        }
        return new PendingPage(rows, page.getTotalElements());
    }

    /** 요청 1건 조회. 크로스허브만 */
    @Transactional(readOnly = true)
    public ChangeRequestView get(Caller caller, String id) {
        requireCrosshub(caller);
        return view(requests.findById(id).orElseThrow(ChangeRequestService::notFound));
    }

    /**
     * 승인 + 반영. 대기중일 때만 승인으로 바뀌므로(조건부 UPDATE) 동시에 두 명이 승인하거나 승인과 반려가 겹쳐도 한 명만 성공한다.
     * 이미 처리됐으면 notFound. 반영이 ProductException 으로 실패하면 트랜잭션이 통째로 롤백돼 승인 표시도 사라진다.
     * 삭제 요청은 상품이 지워지며 요청 행도 cascade 로 같이 사라진다.
     */
    @Transactional
    public ChangeRequestView approve(Caller caller, String id) {
        requireCrosshub(caller);
        ProductChangeRequest r = requests.findById(id).orElseThrow(ChangeRequestService::notFound);
        if (requests.markApproved(id, caller.userId(), OffsetDateTime.now()) == 0) throw notFound();
        Product p = products.findById(r.getProductId()).orElseThrow(ChangeRequestService::notFound);
        String productName = p.getName();
        String partnerId = p.getPartnerId();

        switch (r.getType()) {
            case "delete" -> productService.delete(caller, r.getProductId(), Origin.VIA_REQUEST);
            case "edit" -> productService.update(caller, r.getProductId(), upsertFromProposed(r.getProposedData()), false, Origin.VIA_REQUEST);
            case "hide" -> productService.setStatus(caller, r.getProductId(), new ProductStatusRequest(r.getTargetStatus(), null), Origin.VIA_REQUEST);
            default -> throw notFound();
        }

        notifier.notify(notifier.partnerStakeholders(partnerId, false, true, true), "product_change_resolved",
                r.getId() + ":approved", ProductService.resolvedPayload(r, p, "approved", null));
        ChangeRequestView view = new ChangeRequestView(r.getId(), r.getProductId(), productName, partnerId,
                r.getType(), r.getTargetStatus(), "approved", null);
        return "delete".equals(r.getType()) ? view : view(requests.findById(id).orElse(r));
    }

    /** 반려. 상품은 건드리지 않고 요청만 rejected. 소유측에 알림 */
    @Transactional
    public ChangeRequestView reject(Caller caller, String id, String reason) {
        requireCrosshub(caller);
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) throw new ProductException(HttpStatus.BAD_REQUEST, "required");
        if (trimmed.length() > REASON_MAX) throw new ProductException(HttpStatus.BAD_REQUEST, "tooLong");
        ProductChangeRequest r = requests.findById(id).orElseThrow(ChangeRequestService::notFound);
        if (requests.markRejected(id, caller.userId(), OffsetDateTime.now(), trimmed) == 0) throw notFound();
        Product p = products.findById(r.getProductId()).orElse(null);
        if (p != null) {
            notifier.notify(notifier.partnerStakeholders(p.getPartnerId(), false, true, true), "product_change_resolved",
                    r.getId() + ":rejected", ProductService.resolvedPayload(r, p, "rejected", trimmed));
        }
        return view(requests.findById(id).orElseThrow(ChangeRequestService::notFound));
    }

    /**
     * 수정 요청 본문(FE ProductInput JSON) → 상품 수정 요청. 판매기간·대표가는 FE 가 저장 때 계산해 보내던 값이라 여기서 계산한다.
     * 업체는 바꾸지 않는다 (요청 본문의 partnerId 는 무시 — ProductService.update 도 partnerId 를 안 쓴다).
     */
    ProductUpsertRequest upsertFromProposed(String proposedJson) {
        if (proposedJson == null || proposedJson.isBlank()) throw new ProductException(HttpStatus.BAD_REQUEST, "required");
        try {
            JsonNode node = objectMapper.readTree(proposedJson);
            ProductUpsertRequest base = objectMapper.reader()
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(objectMapper.treeAsTokens(node), ProductUpsertRequest.class);
            SaleRange.Range range = SaleRange.of(node.path("schedule").isMissingNode() ? null : node.get("schedule").toString(), objectMapper);
            return new ProductUpsertRequest(base.partnerId(), base.name(), base.category(), base.confirmType(), base.description(),
                    base.sourceLocale(), base.images(), base.schedule(), base.cancelRules(), base.priceTable(), base.blocks(),
                    range == null ? null : range.start(), range == null ? null : range.end(),
                    base.deadlineDays(), base.deadlineTime(), PriceFrom.of(base.priceTable()));
        } catch (JsonProcessingException | java.io.UncheckedIOException e) {
            throw new ProductException(HttpStatus.BAD_REQUEST, "required");
        } catch (java.io.IOException e) {
            throw new ProductException(HttpStatus.BAD_REQUEST, "required");
        }
    }

    private String requesterName(Caller caller) {
        return users.findById(caller.userId()).map(UserAccount::getName).orElse(caller.loginId());
    }

    private ChangeRequestView view(ProductChangeRequest r) {
        Product p = products.findById(r.getProductId()).orElse(null);
        JsonNode proposed = null;
        if (r.getProposedData() != null) {
            try {
                proposed = com.crosshub.backoffice.product.ProductImages.toUrls(objectMapper.readTree(r.getProposedData()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        return new ChangeRequestView(r.getId(), r.getProductId(), p != null ? p.getName() : null, p != null ? p.getPartnerId() : null,
                r.getType(), r.getTargetStatus(), r.getStatus(), proposed);
    }

    private static void requireCrosshub(Caller caller) {
        if (!caller.isCrosshub()) throw new ProductException(HttpStatus.FORBIDDEN, "forbidden");
    }

    private static ProductException notFound() {
        return new ProductException(HttpStatus.NOT_FOUND, "notFound");
    }
}
