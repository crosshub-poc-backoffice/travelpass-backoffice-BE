package com.crosshub.backoffice.payment.dto;

import com.crosshub.backoffice.payment.PgSetting;

/** feeRate 는 크로스허브에게만 (그 외엔 null) — 업체 수수료·연맹 몫과 합치면 크로스허브 마진이 나온다. 대시보드·정산 화면은 pgName·payDay 만 쓴다 */
public record PgSettingResponse(String currency, String pgName, Double feeRate, int payDay) {
    public static PgSettingResponse from(PgSetting s, boolean withFeeRate) {
        return new PgSettingResponse(
                s.getCurrency(), s.getPgName(),
                withFeeRate ? (s.getFeeRate() == null ? 0 : s.getFeeRate()) : null,
                s.getPayDay() == null ? 0 : s.getPayDay());
    }
}
