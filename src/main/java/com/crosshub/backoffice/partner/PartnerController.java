package com.crosshub.backoffice.partner;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.partner.dto.PartnerResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업체 조회 API.
 *  GET /api/v1/partners        (crosshub 전체, FE가 연맹 범위 필터)
 *  GET /api/v1/partners/{id}
 */
@RestController
@RequestMapping("/api/v1/partners")
public class PartnerController {

    private final PartnerService service;
    private final CallerService callers;

    public PartnerController(PartnerService service, CallerService callers) {
        this.service = service;
        this.callers = callers;
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
