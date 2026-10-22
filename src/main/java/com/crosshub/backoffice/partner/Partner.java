package com.crosshub.backoffice.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * v1 "Partner" 테이블 매핑.
 * 다른 도메인(Booking/Product 조인)은 name/currency만 읽지만, 업체 관리 화면용으로 전체 필드를 둔다.
 * 민감정보(은행계좌 등)의 역할별 마스킹은 FE 매핑에서 처리한다(BE는 원값).
 */
@Entity
@Table(name = "Partner")
public class Partner {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name")
    private String name;

    @Column(name = "proxyAffiliateId")
    private String proxyAffiliateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proxyAffiliateId", insertable = false, updatable = false)
    private Affiliate proxyAffiliate;

    @Column(name = "manager")
    private String manager;

    @Column(name = "email")
    private String email;

    @Column(name = "active")
    private boolean active;

    @Column(name = "commissionRate")
    private Integer commissionRate;

    @Column(name = "currency")
    private String currency;

    @Column(name = "businessRegistrationNumber")
    private String businessRegistrationNumber;

    @Column(name = "phone")
    private String phone;

    @Column(name = "address")
    private String address;

    @Column(name = "bankName")
    private String bankName;

    @Column(name = "bankAccountNumber")
    private String bankAccountNumber;

    @Column(name = "bankAccountHolder")
    private String bankAccountHolder;

    @Column(name = "bankCode")
    private String bankCode;

    @Column(name = "memo")
    private String memo;

    @Column(name = "representativeName")
    private String representativeName;

    @Column(name = "corporateName")
    private String corporateName;

    @Column(name = "businessLicenseUrl")
    private String businessLicenseUrl;

    @Column(name = "subManager")
    private String subManager;

    @Column(name = "subManagerPhone")
    private String subManagerPhone;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    @Column(name = "updatedAt")
    private OffsetDateTime updatedAt;

    protected Partner() {
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProxyAffiliateId() { return proxyAffiliateId; }
    public Affiliate getProxyAffiliate() { return proxyAffiliate; }
    public String getManager() { return manager; }
    public String getEmail() { return email; }
    public boolean isActive() { return active; }
    public Integer getCommissionRate() { return commissionRate; }
    public String getCurrency() { return currency; }
    public String getBusinessRegistrationNumber() { return businessRegistrationNumber; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public String getBankName() { return bankName; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public String getBankAccountHolder() { return bankAccountHolder; }
    public String getBankCode() { return bankCode; }
    public String getMemo() { return memo; }
    public String getRepresentativeName() { return representativeName; }
    public String getCorporateName() { return corporateName; }
    public String getBusinessLicenseUrl() { return businessLicenseUrl; }
    public String getSubManager() { return subManager; }
    public String getSubManagerPhone() { return subManagerPhone; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
