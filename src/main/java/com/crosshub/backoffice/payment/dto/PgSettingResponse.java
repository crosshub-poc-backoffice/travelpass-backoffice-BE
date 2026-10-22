package com.crosshub.backoffice.payment.dto;

import com.crosshub.backoffice.payment.PgSetting;

public record PgSettingResponse(String currency, String pgName, double feeRate, int payDay) {
    public static PgSettingResponse from(PgSetting s) {
        return new PgSettingResponse(
                s.getCurrency(), s.getPgName(),
                s.getFeeRate() == null ? 0 : s.getFeeRate(),
                s.getPayDay() == null ? 0 : s.getPayDay());
    }
}
