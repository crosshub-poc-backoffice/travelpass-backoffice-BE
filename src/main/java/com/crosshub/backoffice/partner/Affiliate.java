package com.crosshub.backoffice.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** v1 "Affiliate"(연맹) 테이블. 이름·몫·정산계좌. */
@Entity
@Table(name = "Affiliate")
public class Affiliate {
    @Id @Column(name = "id") private String id;
    @Column(name = "name") private String name;
    @Column(name = "shareRate") private Integer shareRate;
    @Column(name = "bankName") private String bankName;
    @Column(name = "bankAccountNumber") private String bankAccountNumber;
    @Column(name = "bankAccountHolder") private String bankAccountHolder;

    protected Affiliate() {}
    public String getId() { return id; }
    public String getName() { return name; }
    public Integer getShareRate() { return shareRate; }
    public String getBankName() { return bankName; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public String getBankAccountHolder() { return bankAccountHolder; }
}
