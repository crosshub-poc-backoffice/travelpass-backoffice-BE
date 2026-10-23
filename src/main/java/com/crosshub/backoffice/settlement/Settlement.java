package com.crosshub.backoffice.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** v1 "Settlement" — 업체 월 정산. 지급완료일 때만 행이 있다(지급 취소 = 행 삭제). (partnerId, period) 유니크 */
@Entity
@Table(name = "Settlement")
public class Settlement {
    @Id @Column(name = "id") private String id;
    @Column(name = "partnerId") private String partnerId;
    @Column(name = "period") private String period;
    @Column(name = "gross") private int gross;
    @Column(name = "commissionRate") private int commissionRate;
    @Column(name = "commission") private int commission;
    @Column(name = "net") private int net;
    @Column(name = "bookingCount") private int bookingCount;
    @Column(name = "status") private String status;
    @Column(name = "payDate") private LocalDate payDate;
    @Column(name = "createdAt") private OffsetDateTime createdAt;
    @Column(name = "updatedAt") private OffsetDateTime updatedAt;

    protected Settlement() {}

    public static Settlement paid(String partnerId, String period, int gross, int commissionRate, int commission,
                                  int net, int bookingCount, LocalDate payDate) {
        Settlement s = new Settlement();
        s.id = UUID.randomUUID().toString().replace("-", "");
        s.partnerId = partnerId;
        s.period = period;
        s.gross = gross;
        s.commissionRate = commissionRate;
        s.commission = commission;
        s.net = net;
        s.bookingCount = bookingCount;
        s.status = "paid";
        s.payDate = payDate;
        s.createdAt = s.updatedAt = OffsetDateTime.now();
        return s;
    }

    public String getId() { return id; }
    public String getPartnerId() { return partnerId; }
    public String getPeriod() { return period; }
    public int getGross() { return gross; }
    public int getCommission() { return commission; }
    public int getNet() { return net; }
    public int getBookingCount() { return bookingCount; }
    public LocalDate getPayDate() { return payDate; }
}
