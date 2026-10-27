package com.crosshub.backoffice.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 정산 응답 모양. FE lib/server/settlements/service.ts의 SettlementLine·SettlementRow·AffiliateSettlementRow와 같은 필드 */
public final class SettlementRows {
    private SettlementRows() {}

    public record Line(
            String bookingId, String productId, String productName, String useDate, String status,
            int base, int commissionRate, int commission, int net,
            @JsonProperty("isCancel") boolean isCancel,
            Integer affiliateShareRate, Integer affiliate, Double pgFeeRate, Integer pg) {

        /** 연맹 몫·PG 수수료를 뺀 줄 (크로스허브가 아닌 사람에게 줄 때) */
        public Line withoutDistribution() {
            return new Line(bookingId, productId, productName, useDate, status, base, commissionRate, commission, net, isCancel,
                    null, null, null, null);
        }
    }

    public record Distribution(
            String pgName, String pgFeeRateLabel, int pg,
            String affiliateName, String affiliateShareRateLabel, int affiliate, int crosshub) {}

    public record Row(
            String partnerId, String partnerName, String currency, String commissionRateLabel,
            int gross, int commission, int net, int bookingCount, String status, String payDate,
            List<Line> lines, String bankAccount, Distribution distribution) {}

    public record AffiliateRow(
            String affiliateId, String affiliateName, String currency, String shareRateLabel,
            int amount, int partnerCount, String status, String payDate, String bankAccount) {}

    public record PayRequest(String partnerId, String affiliateId, String period, String currency, String payDate) {}
}
