package com.crosshub.backoffice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** v1 "PgSetting" — 통화별 PG 결제 설정. currency가 PK. */
@Entity
@Table(name = "PgSetting")
public class PgSetting {
    @Id @Column(name = "currency") private String currency;
    @Column(name = "pgName") private String pgName;
    @Column(name = "feeRate") private Double feeRate;
    @Column(name = "payDay") private Integer payDay;

    protected PgSetting() {}
    public String getCurrency() { return currency; }
    public String getPgName() { return pgName; }
    public Double getFeeRate() { return feeRate; }
    public Integer getPayDay() { return payDay; }
}
