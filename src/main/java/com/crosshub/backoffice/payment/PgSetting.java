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
    @Column(name = "updatedAt") private java.time.OffsetDateTime updatedAt; // Prisma @updatedAt — 직접 채운다 (basis는 DB 기본값)

    protected PgSetting() {}

    public static PgSetting create(String currency) {
        PgSetting s = new PgSetting();
        s.currency = currency;
        return s;
    }

    public void update(String pgName, double feeRate, int payDay) {
        this.pgName = pgName;
        this.feeRate = feeRate;
        this.payDay = payDay;
        this.updatedAt = java.time.OffsetDateTime.now();
    }
    public String getCurrency() { return currency; }
    public String getPgName() { return pgName; }
    public Double getFeeRate() { return feeRate; }
    public Integer getPayDay() { return payDay; }
}
