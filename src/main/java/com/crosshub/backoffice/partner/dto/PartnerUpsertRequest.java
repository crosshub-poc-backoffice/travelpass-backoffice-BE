package com.crosshub.backoffice.partner.dto;

/**
 * 업체 등록·수정 (POST /api/v1/partners, PUT /api/v1/partners/{id}).
 * FE partners/actions.ts의 parse()가 다듬은 값을 그대로 받는다(빈 칸은 null).
 * proxyAffiliateId·commissionRate·currency는 크로스허브가 보낸 값만 쓰고, memo는 업체 계정 값은 무시한다.
 */
public record PartnerUpsertRequest(
        String name,
        String proxyAffiliateId,
        Integer commissionRate,
        String currency,
        String managerName,
        String phone,
        String email,
        String bankName,
        String bankAccountNumber,
        String bankAccountHolder,
        String memo,
        String businessRegistrationNumber,
        String representativeName,
        String corporateName,
        String address,
        String businessLicenseUrl,
        String subManager,
        String subManagerPhone,
        String bankCode) {
}
