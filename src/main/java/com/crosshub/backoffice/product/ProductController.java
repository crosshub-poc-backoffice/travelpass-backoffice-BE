package com.crosshub.backoffice.product;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.product.dto.ProductDetailResponse;
import com.crosshub.backoffice.product.dto.ProductStatusRequest;
import com.crosshub.backoffice.product.dto.ProductSummaryResponse;
import com.crosshub.backoffice.product.dto.ProductUpsertRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 API.
 *  - URL 레벨 권한(어느 role이 호출 가능한가)은 SecurityConfig에서 토큰 role로 건다.
 *  - 업체 범위(누구의 상품인가)는 여기서 Caller(= User 테이블 조회)로 건다.
 *
 *  GET    /api/v1/products?partnerId=&status=   (partner는 본인 업체만 자동 적용)
 *  GET    /api/v1/products/{id}
 *  POST   /api/v1/products?draft=         draft=true면 임시저장, 아니면 바로 심사중
 *  PUT    /api/v1/products/{id}?submit=   submit=true면 임시저장 상품을 심사중으로
 *  POST   /api/v1/products/{id}/status    { status, reason? } 상태 전환(승인·반려·노출·숨김)
 *  DELETE /api/v1/products/{id}
 *  실패 응답: {code} (FE 에러 키와 같은 이름: partnerInactive·approveCrosshubOnly·commissionMissing·required·hasBookings ...)
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService service;
    private final CallerService callers;

    public ProductController(ProductService service, CallerService callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping
    public List<ProductSummaryResponse> list(
            @RequestParam(required = false) String status) {
        Caller caller = callers.current();
        return service.list(caller, status);
    }

    @GetMapping("/{id}")
    public ProductDetailResponse get(@PathVariable String id) {
        Caller caller = callers.current();
        return service.get(caller, id);
    }

    @PostMapping
    public ResponseEntity<ProductDetailResponse> create(
            @RequestBody ProductUpsertRequest req,
            @RequestParam(defaultValue = "false") boolean draft) {
        Caller caller = callers.current();
        ProductDetailResponse created = service.create(caller, req, draft);
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    public ProductDetailResponse update(
            @PathVariable String id,
            @RequestBody ProductUpsertRequest req,
            @RequestParam(defaultValue = "false") boolean submit) {
        Caller caller = callers.current();
        return service.update(caller, id, req, submit);
    }

    @PostMapping("/{id}/status")
    public ProductDetailResponse setStatus(
            @PathVariable String id,
            @RequestBody ProductStatusRequest req) {
        Caller caller = callers.current();
        return service.setStatus(caller, id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Caller caller = callers.current();
        service.delete(caller, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ProductException.class)
    public ResponseEntity<Map<String, String>> onProductError(ProductException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }
}
