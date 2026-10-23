package com.crosshub.backoffice.payment;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.payment.dto.AffiliateShareResponse;
import com.crosshub.backoffice.payment.dto.PgSettingResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 설정 조회 API.
 *  GET /api/v1/pg-settings        통화별 PG 설정 — 로그인한 모든 역할 (대시보드·정산의 PG명/지급일 표시용, 민감정보 없음)
 *  GET /api/v1/affiliate-shares   연맹 몫·정산 계좌 — 크로스허브 전용 (계좌번호 포함)
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentConfigService service;
    private final CallerService callers;

    public PaymentController(PaymentConfigService service, CallerService callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping("/pg-settings")
    public List<PgSettingResponse> pgSettings() {
        callers.current();   // 등록·활성 계정인지 확인
        return service.listPgSettings();
    }

    @GetMapping("/affiliate-shares")
    public List<AffiliateShareResponse> affiliateShares() {
        callers.requireCrosshub();
        return service.listAffiliateShares();
    }
}
