package com.crosshub.backoffice.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * v1 "Partner" 테이블 매핑.
 * 다른 도메인(Product 조인)은 name/currency만 읽지만, 업체 관리 화면용으로 전체 필드를 둔다.
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

    /** 사업자등록증 파일의 저장소 안 경로 (예: partners/….pdf). URL 은 응답 때 만든다 (storage/StoredFiles) */
    @Column(name = "businessLicenseKey")
    private String businessLicenseKey;

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

    /** 새 업체. id·createdAt·updatedAt·active(기본 true)를 채운다 — Prisma @default/@updatedAt은 DB 기본값이 아니라서 */
    public static Partner create(String name, String proxyAffiliateId, String currency) {
        Partner p = new Partner();
        p.id = UUID.randomUUID().toString().replace("-", "");
        p.name = name;
        p.proxyAffiliateId = proxyAffiliateId;
        p.currency = currency;
        p.active = true;
        p.createdAt = p.updatedAt = OffsetDateTime.now();
        return p;
    }

    public void setName(String v) { this.name = v; }
    public void setProxyAffiliateId(String v) { this.proxyAffiliateId = v; }
    public void setManager(String v) { this.manager = v; }
    public void setEmail(String v) { this.email = v; }
    public void setActive(boolean v) { this.active = v; }
    public void setCommissionRate(Integer v) { this.commissionRate = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setBusinessRegistrationNumber(String v) { this.businessRegistrationNumber = v; }
    public void setPhone(String v) { this.phone = v; }
    public void setAddress(String v) { this.address = v; }
    public void setBankName(String v) { this.bankName = v; }
    public void setBankAccountNumber(String v) { this.bankAccountNumber = v; }
    public void setBankAccountHolder(String v) { this.bankAccountHolder = v; }
    public void setBankCode(String v) { this.bankCode = v; }
    public void setMemo(String v) { this.memo = v; }
    public void setRepresentativeName(String v) { this.representativeName = v; }
    public void setCorporateName(String v) { this.corporateName = v; }
    public void setBusinessLicenseKey(String v) { this.businessLicenseKey = v; }
    public void setSubManager(String v) { this.subManager = v; }
    public void setSubManagerPhone(String v) { this.subManagerPhone = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }

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
    public String getBusinessLicenseKey() { return businessLicenseKey; }
    public String getSubManager() { return subManager; }
    public String getSubManagerPhone() { return subManagerPhone; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
