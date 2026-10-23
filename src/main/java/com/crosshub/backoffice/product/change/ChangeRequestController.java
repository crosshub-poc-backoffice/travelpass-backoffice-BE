package com.crosshub.backoffice.product.change;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.product.ProductException;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.ChangeRequestView;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.CreateRequest;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.Created;
import com.crosshub.backoffice.product.change.ChangeRequestDtos.RejectRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 변경요청 API.
 *  POST /api/v1/products/{productId}/change-requests   { type, targetStatus?, proposedData? }  요청 생성 (범위 안 누구나)
 *  GET  /api/v1/change-requests/{id}                   요청 1건 (크로스허브)
 *  POST /api/v1/change-requests/{id}/approve           승인 표시 (크로스허브, 반영은 FE가 먼저)
 *  POST /api/v1/change-requests/{id}/reject            { reason } 반려 (크로스허브)
 *  실패 응답: {code} (notFound·duplicatePending·required·forbidden)
 */
@RestController
@RequestMapping("/api/v1")
public class ChangeRequestController {

    private final ChangeRequestService service;
    private final CallerService callers;

    public ChangeRequestController(ChangeRequestService service, CallerService callers) {
        this.service = service;
        this.callers = callers;
    }

    @PostMapping("/products/{productId}/change-requests")
    public ResponseEntity<Created> create(@PathVariable String productId, @RequestBody CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new Created(service.create(callers.current(), productId, req)));
    }

    @GetMapping("/change-requests/{id}")
    public ChangeRequestView get(@PathVariable String id) {
        return service.get(callers.current(), id);
    }

    @PostMapping("/change-requests/{id}/approve")
    public ChangeRequestView approve(@PathVariable String id) {
        return service.approve(callers.current(), id);
    }

    @PostMapping("/change-requests/{id}/reject")
    public ChangeRequestView reject(@PathVariable String id, @RequestBody RejectRequest req) {
        return service.reject(callers.current(), id, req.reason());
    }

    @ExceptionHandler(ProductException.class)
    public ResponseEntity<Map<String, String>> onError(ProductException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }
}
