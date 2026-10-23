package com.crosshub.backoffice.settlement;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.settlement.dto.SettlementRows.AffiliateRow;
import com.crosshub.backoffice.settlement.dto.SettlementRows.PayRequest;
import com.crosshub.backoffice.settlement.dto.SettlementRows.Row;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정산 API.
 *  GET  /api/v1/settlements?month=YYYY-MM&partnerId=     업체별 월 정산 (partnerId = 업체 전환, 선택)
 *  GET  /api/v1/settlements/affiliates?month=YYYY-MM     연맹 몫
 *  POST /api/v1/settlements/pay        { partnerId, period, payDate }     지급완료 (크로스허브)
 *  POST /api/v1/settlements/unpay      { partnerId, period }              지급완료 취소 (크로스허브)
 *  POST /api/v1/settlements/affiliates/pay    { affiliateId, period, currency, payDate }
 *  POST /api/v1/settlements/affiliates/unpay  { affiliateId, period, currency }
 *  실패 응답: {code} (monthNotEnded·unfinishedBookings·alreadyPaid·notFound·forbidden)
 */
@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementService service;
    private final CallerService callers;

    public SettlementController(SettlementService service, CallerService callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping
    public List<Row> monthly(@RequestParam String month, @RequestParam(required = false) String partnerId) {
        return service.listMonthly(callers.current(), month, partnerId);
    }

    @GetMapping("/affiliates")
    public List<AffiliateRow> affiliates(@RequestParam String month) {
        return service.listAffiliates(callers.current(), month);
    }

    @PostMapping("/pay")
    public ResponseEntity<Void> pay(@RequestBody PayRequest req) {
        service.markPaid(callers.current(), req.partnerId(), req.period(), req.payDate());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unpay")
    public ResponseEntity<Void> unpay(@RequestBody PayRequest req) {
        service.unmarkPaid(callers.current(), req.partnerId(), req.period());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/affiliates/pay")
    public ResponseEntity<Void> payAffiliate(@RequestBody PayRequest req) {
        service.markAffiliatePaid(callers.current(), req.affiliateId(), req.period(), req.currency(), req.payDate());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/affiliates/unpay")
    public ResponseEntity<Void> unpayAffiliate(@RequestBody PayRequest req) {
        service.unmarkAffiliatePaid(callers.current(), req.affiliateId(), req.period(), req.currency());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(SettlementException.class)
    public ResponseEntity<Map<String, String>> onError(SettlementException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }
}
