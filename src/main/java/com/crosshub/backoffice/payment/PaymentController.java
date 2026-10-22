package com.crosshub.backoffice.payment;

import com.crosshub.backoffice.payment.dto.AffiliateShareResponse;
import com.crosshub.backoffice.payment.dto.PgSettingResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 설정 조회 API (크로스허브 전용).
 *  GET /api/v1/pg-settings        통화별 PG 설정
 *  GET /api/v1/affiliate-shares   연맹 몫·정산 계좌
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentConfigService service;

    public PaymentController(PaymentConfigService service) {
        this.service = service;
    }

    @GetMapping("/pg-settings")
    public List<PgSettingResponse> pgSettings() {
        return service.listPgSettings();
    }

    @GetMapping("/affiliate-shares")
    public List<AffiliateShareResponse> affiliateShares() {
        return service.listAffiliateShares();
    }
}
