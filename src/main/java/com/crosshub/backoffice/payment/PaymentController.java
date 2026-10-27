package com.crosshub.backoffice.payment;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.payment.dto.AffiliateShareResponse;
import com.crosshub.backoffice.payment.dto.PgSettingResponse;
import com.crosshub.backoffice.common.PageResponse;
import com.crosshub.backoffice.payment.dto.PaymentDtos.AffiliateShareRequest;
import com.crosshub.backoffice.payment.dto.PaymentDtos.BankRequest;
import com.crosshub.backoffice.payment.dto.PaymentDtos.MyAffiliateResponse;
import com.crosshub.backoffice.payment.dto.PaymentDtos.PaymentRow;
import com.crosshub.backoffice.payment.dto.PaymentDtos.PgSettingRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 설정 조회 API.
 *  GET /api/v1/pg-settings        통화별 PG 설정 — 로그인한 모든 역할 (대시보드·정산의 PG명/지급일 표시용. feeRate 는 크로스허브에게만)
 *  GET /api/v1/affiliate-shares   연맹 몫·정산 계좌 — 크로스허브 전용 (계좌번호 포함)
 *  PUT /api/v1/pg-settings/{currency}       { pgName, feeRate, payDay } PG 설정 저장 (크로스허브)
 *  PUT /api/v1/affiliate-shares/{id}        { shareRate, bankName, bankAccountNumber, bankAccountHolder } (크로스허브)
 *  GET /api/v1/my-affiliate                 연맹 계정의 내 연맹 정보
 *  PUT /api/v1/my-affiliate/bank            { bankName, bankAccountNumber, bankAccountHolder } 연맹 계정이 자기 계좌 수정
 *  GET /api/v1/payments?from=&to=&type=&page=&partnerId=   결제내역 (from·to JST 날짜)
 *  실패 응답: {code}
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentConfigService service;
    private final PaymentCommandService commands;
    private final CallerService callers;

    public PaymentController(PaymentConfigService service, PaymentCommandService commands, CallerService callers) {
        this.service = service;
        this.commands = commands;
        this.callers = callers;
    }

    @PutMapping("/pg-settings/{currency}")
    public ResponseEntity<Void> savePgSetting(@PathVariable String currency, @RequestBody PgSettingRequest req) {
        commands.savePgSetting(callers.current(), currency, req);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/affiliate-shares/{id}")
    public ResponseEntity<Void> saveAffiliateShare(@PathVariable String id, @RequestBody AffiliateShareRequest req) {
        commands.saveAffiliateShare(callers.current(), id, req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/my-affiliate")
    public MyAffiliateResponse myAffiliate() {
        return commands.getMyAffiliate(callers.current());
    }

    @PutMapping("/my-affiliate/bank")
    public ResponseEntity<Void> saveMyAffiliateBank(@RequestBody BankRequest req) {
        commands.saveMyAffiliateBank(callers.current(), req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payments")
    public PageResponse<PaymentRow> payments(@RequestParam String from, @RequestParam String to,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(required = false) String partnerId) {
        return commands.listPayments(callers.current(), from, to, type, page, partnerId);
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<Map<String, String>> onError(PaymentException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }

    @GetMapping("/pg-settings")
    public List<PgSettingResponse> pgSettings() {
        return service.listPgSettings(callers.current().isCrosshub()); // 수수료율은 크로스허브에게만
    }

    @GetMapping("/affiliate-shares")
    public List<AffiliateShareResponse> affiliateShares() {
        callers.requireCrosshub();
        return service.listAffiliateShares();
    }
}
