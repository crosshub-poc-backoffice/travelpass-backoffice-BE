package com.crosshub.backoffice.partner;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.partner.dto.PartnerResponse;
import com.crosshub.backoffice.partner.dto.PartnerToggleRequest;
import com.crosshub.backoffice.partner.dto.PartnerUpsertRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업체 조회 API.
 *  GET /api/v1/partners        (crosshub 전체, FE가 연맹 범위 필터)
 *  GET /api/v1/partners/{id}
 *  POST /api/v1/partners                         업체 등록 (크로스허브·연맹)
 *  PUT  /api/v1/partners/{id}                    업체 수정 (관리 범위)
 *  POST /api/v1/partners/{id}/toggle-active      { restoreProducts } 활성화·비활성화 (크로스허브)
 *  실패 응답: {code} (FE 에러 키: partnerNameTaken·invalidOrg·currencyLocked·proxyLocked·commissionBelowShares ...)
 */
@RestController
@RequestMapping("/api/v1/partners")
public class PartnerController {

    private final PartnerService service;
    private final PartnerCommandService commands;
    private final CallerService callers;

    public PartnerController(PartnerService service, PartnerCommandService commands, CallerService callers) {
        this.service = service;
        this.commands = commands;
        this.callers = callers;
    }

    @PostMapping
    public ResponseEntity<PartnerResponse> create(@RequestBody PartnerUpsertRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(commands.create(callers.current(), req));
    }

    @PutMapping("/{id}")
    public PartnerResponse update(@PathVariable String id, @RequestBody PartnerUpsertRequest req) {
        return commands.update(callers.current(), id, req);
    }

    @PostMapping("/{id}/toggle-active")
    public PartnerResponse toggleActive(@PathVariable String id, @RequestBody(required = false) PartnerToggleRequest req) {
        return commands.toggleActive(callers.current(), id, req != null && req.restoreProducts());
    }

    @ExceptionHandler(PartnerException.class)
    public ResponseEntity<Map<String, String>> onPartnerError(PartnerException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }

    @GetMapping
    public List<PartnerResponse> list() {
        Caller caller = callers.current();
        return service.list(caller);
    }

    @GetMapping("/{id}")
    public PartnerResponse get(@PathVariable String id) {
        Caller caller = callers.current();
        return service.get(caller, id);
    }
}
