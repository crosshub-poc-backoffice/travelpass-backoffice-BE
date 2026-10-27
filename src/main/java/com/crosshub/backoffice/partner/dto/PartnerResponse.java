package com.crosshub.backoffice.partner.dto;

import com.crosshub.backoffice.partner.Partner;
import java.time.OffsetDateTime;

/**
 * 업체 관리용 응답 (원값). 역할별 마스킹(memo 숨김, 계좌 마스킹)은 FE에서 처리한다.
 * proxyAffiliateName = 대행 연맹 이름, hiddenByOffCount = 비활성화로 숨김된 상품 수.
 */
public record PartnerResponse(
        String id,
        String name,
        String proxyAffiliateId,
        String proxyAffiliateName,
        boolean active,
        Integer commissionRate,
        String currency,
        String manager,
        String phone,
        String email,
        String bankName,
        String bankAccountNumber,
        String bankAccountHolder,
        String bankCode,
        String memo,
        String businessRegistrationNumber,
        String representativeName,
        String corporateName,
        String address,
        String businessLicenseUrl,
        String subManager,
        String subManagerPhone,
        long hiddenByOffCount,
        OffsetDateTime createdAt) {

    public static PartnerResponse from(Partner p, long hiddenByOffCount) {
        return from(p, p.getProxyAffiliate() != null ? p.getProxyAffiliate().getName() : null, hiddenByOffCount);
    }

    /** 대행 연맹 이름을 직접 받는 버전 — 쓰기 직후엔 지연 조인(proxyAffiliate)이 비어 있거나 옛 값이라서 */
    public static PartnerResponse from(Partner p, String affName, long hiddenByOffCount) {
        return new PartnerResponse(
                p.getId(), p.getName(), p.getProxyAffiliateId(), affName, p.isActive(),
                p.getCommissionRate(), p.getCurrency(), p.getManager(), p.getPhone(), p.getEmail(),
                p.getBankName(), p.getBankAccountNumber(), p.getBankAccountHolder(), p.getBankCode(),
                p.getMemo(), p.getBusinessRegistrationNumber(), p.getRepresentativeName(),
                p.getCorporateName(), p.getAddress(), com.crosshub.backoffice.storage.StoredFiles.url(p.getBusinessLicenseKey()),
                p.getSubManager(), p.getSubManagerPhone(), hiddenByOffCount, p.getCreatedAt());
    }
}
