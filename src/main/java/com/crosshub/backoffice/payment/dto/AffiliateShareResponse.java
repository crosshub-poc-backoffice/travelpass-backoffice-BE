package com.crosshub.backoffice.payment.dto;

import com.crosshub.backoffice.partner.Affiliate;

public record AffiliateShareResponse(
        String id, String name, Integer shareRate,
        String bankName, String bankAccountNumber, String bankAccountHolder) {
    public static AffiliateShareResponse from(Affiliate a) {
        return new AffiliateShareResponse(
                a.getId(), a.getName(), a.getShareRate(),
                a.getBankName(), a.getBankAccountNumber(), a.getBankAccountHolder());
    }
}
