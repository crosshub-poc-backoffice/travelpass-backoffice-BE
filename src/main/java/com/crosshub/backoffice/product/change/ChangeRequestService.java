package com.crosshub.backoffice.product.change;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductException;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.ChangeRequestView;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.CreateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 변경요청. FE lib/server/products/change-requests.ts의 쓰기 부분을 옮긴 것.
 *  - 생성: 상품을 다룰 수 있는 사람(범위 안). live/hidden 상품만, 대기중 요청이 이미 있으면 duplicatePending.
 *  - 승인 표시·반려: 크로스허브만. 대기중(pending) 요청만.
 * 실제 반영(수정·삭제·상태 전환)은 이미 BE에 있는 상품 API를 FE가 승인 전에 부른다(기존 FE 흐름과 같음).
 * 삭제 요청은 승인 때 상품이 지워지며 이 행도 cascade로 같이 사라지므로 승인 표시를 따로 하지 않는다.
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
    private final ObjectMapper objectMapper;

    public ChangeRequestService(ProductChangeRequestRepository requests, ProductRepository products, ObjectMapper objectMapper) {
        this.requests = requests;
        this.products = products;
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
            proposed = req.proposedData().toString();
            if (proposed.length() > PROPOSED_MAX) throw new ProductException(HttpStatus.BAD_REQUEST, "tooLong");
        }
        if (requests.existsByProductIdAndStatus(productId, "pending")) throw new ProductException(HttpStatus.CONFLICT, "duplicatePending");

        ProductChangeRequest r = ProductChangeRequest.pending(productId, type, target, proposed, caller.userId());
        requests.saveAndFlush(r);
        return r.getId();
    }

    /** 요청 1건 조회 (승인 반영 전 FE가 내용 확인용). 크로스허브만 */
    @Transactional(readOnly = true)
    public ChangeRequestView get(Caller caller, String id) {
        requireCrosshub(caller);
        return view(requests.findById(id).orElseThrow(ChangeRequestService::notFound));
    }

    /**
     * 승인 표시(선점). FE는 이걸 먼저 불러 "내가 처리한다"를 확정한 뒤 상품 API로 반영하고,
     * 반영이 실패하면 reopen으로 되돌린다. 대기중일 때만 바뀌므로(조건부 UPDATE) 동시에 두 명이 승인하거나
     * 승인과 반려가 겹쳐도 한 명만 성공한다. 이미 처리됐으면 notFound.
     */
    @Transactional
    public ChangeRequestView approve(Caller caller, String id) {
        requireCrosshub(caller);
        ProductChangeRequest r = requests.findById(id).orElseThrow(ChangeRequestService::notFound);
        if (requests.markApproved(id, caller.userId(), OffsetDateTime.now()) == 0) throw notFound();
        return view(requests.findById(id).orElse(r));
    }

    /** 승인 반영 실패 시 되돌리기. 본인이 승인한 요청만 대기중으로 */
    @Transactional
    public ChangeRequestView reopen(Caller caller, String id) {
        requireCrosshub(caller);
        if (requests.reopen(id, caller.userId()) == 0) throw notFound();
        return view(requests.findById(id).orElseThrow(ChangeRequestService::notFound));
    }

    /** 반려. 상품은 건드리지 않고 요청만 rejected. 수동 반려·크로스허브 직접처리로 인한 자동 반려 공용 */
    @Transactional
    public ChangeRequestView reject(Caller caller, String id, String reason) {
        requireCrosshub(caller);
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) throw new ProductException(HttpStatus.BAD_REQUEST, "required");
        if (trimmed.length() > REASON_MAX) throw new ProductException(HttpStatus.BAD_REQUEST, "tooLong");
        requests.findById(id).orElseThrow(ChangeRequestService::notFound);
        if (requests.markRejected(id, caller.userId(), OffsetDateTime.now(), trimmed) == 0) throw notFound();
        return view(requests.findById(id).orElseThrow(ChangeRequestService::notFound));
    }

    private ChangeRequestView view(ProductChangeRequest r) {
        Product p = products.findById(r.getProductId()).orElse(null);
        JsonNode proposed = null;
        if (r.getProposedData() != null) {
            try {
                proposed = objectMapper.readTree(r.getProposedData());
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
