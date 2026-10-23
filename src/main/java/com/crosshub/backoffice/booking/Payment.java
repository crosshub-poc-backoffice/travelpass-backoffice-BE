package com.crosshub.backoffice.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** v1 "Payment" — 예약에 달린 결제·환불 1건. type은 "payment" | "refund" (native enum → String). */
@Entity
@Table(name = "Payment")
public class Payment {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "bookingId")
    private String bookingId;

    @Column(name = "type")
    private String type;

    @Column(name = "amount")
    private int amount;

    @Column(name = "pg")
    private String pg;

    @Column(name = "pgTransactionId")
    private String pgTransactionId;

    @Column(name = "createdAt")
    private OffsetDateTime createdAt;

    protected Payment() {
    }

    public static Payment of(String bookingId, String type, int amount, String pg) {
        Payment p = new Payment();
        p.id = UUID.randomUUID().toString().replace("-", "");
        p.bookingId = bookingId;
        p.type = type;
        p.amount = amount;
        p.pg = pg;
        p.createdAt = OffsetDateTime.now();
        return p;
    }

    public String getId() { return id; }
    public String getBookingId() { return bookingId; }
    public String getType() { return type; }
    public int getAmount() { return amount; }
    public String getPg() { return pg; }
    public String getPgTransactionId() { return pgTransactionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
