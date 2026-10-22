package com.crosshub.backoffice.payment;

import com.crosshub.backoffice.partner.Affiliate;
import com.crosshub.backoffice.partner.AffiliateRepository;
import com.crosshub.backoffice.payment.dto.AffiliateShareResponse;
import com.crosshub.backoffice.payment.dto.PgSettingResponse;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 결제 설정(통화별 PG)·연맹 몫 조회. 크로스허브 전용 메뉴. */
@Service
public class PaymentConfigService {

    private final PgSettingRepository pgSettings;
    private final AffiliateRepository affiliates;

    public PaymentConfigService(PgSettingRepository pgSettings, AffiliateRepository affiliates) {
        this.pgSettings = pgSettings;
        this.affiliates = affiliates;
    }

    @Transactional(readOnly = true)
    public List<PgSettingResponse> listPgSettings() {
        return pgSettings.findAll(Sort.by(Sort.Direction.ASC, "currency"))
                .stream().map(PgSettingResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AffiliateShareResponse> listAffiliateShares() {
        return affiliates.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream().map(AffiliateShareResponse::from).toList();
    }
}
