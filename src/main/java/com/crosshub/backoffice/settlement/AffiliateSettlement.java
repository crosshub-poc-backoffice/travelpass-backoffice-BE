package com.crosshub.backoffice.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** v1 "AffiliateSettlement" — 연맹 몫 월 지급. 지급완료일 때만 행이 있다. (affiliateId, period, currency) 유니크 */
@Entity
@Table(name = "AffiliateSettlement")
public class AffiliateSettlement {
    @Id @Column(name = "id") private String id;
    @Column(name = "affiliateId") private String affiliateId;
    @Column(name = "period") private String period;
    @Column(name = "currency") private String currency;
    @Column(name = "amount") private int amount;
    @Column(name = "partnerCount") private int partnerCount;
    @Column(name = "status") private String status;
    @Column(name = "payDate") private LocalDate payDate;
    @Column(name = "createdAt") private OffsetDateTime createdAt;
    @Column(name = "updatedAt") private OffsetDateTime updatedAt;

    protected AffiliateSettlement() {}

    public static AffiliateSettlement paid(String affiliateId, String period, String currency, int amount,
                                           int partnerCount, LocalDate payDate) {
        AffiliateSettlement s = new AffiliateSettlement();
        s.id = UUID.randomUUID().toString().replace("-", "");
        s.affiliateId = affiliateId;
        s.period = period;
        s.currency = currency;
        s.amount = amount;
        s.partnerCount = partnerCount;
        s.status = "paid";
        s.payDate = payDate;
        s.createdAt = s.updatedAt = OffsetDateTime.now();
        return s;
    }

    public String getId() { return id; }
    public String getAffiliateId() { return affiliateId; }
    public String getPeriod() { return period; }
    public String getCurrency() { return currency; }
    public int getAmount() { return amount; }
    public int getPartnerCount() { return partnerCount; }
    public LocalDate getPayDate() { return payDate; }
}
