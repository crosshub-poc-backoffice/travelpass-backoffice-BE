package com.crosshub.backoffice.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** v1 "Affiliate"(연맹) 테이블. */
@Entity
@Table(name = "Affiliate")
public class Affiliate {
    @Id @Column(name = "id") private String id;
    @Column(name = "name") private String name;
    @Column(name = "createdAt") private OffsetDateTime createdAt;
    @Column(name = "updatedAt") private OffsetDateTime updatedAt; // Prisma @updatedAt — DB 기본값이 없어 직접 채운다

    protected Affiliate() {}

    /** 새 연맹 (계정 발급 때 같이 만든다) */
    public static Affiliate create(String name) {
        Affiliate a = new Affiliate();
        a.id = UUID.randomUUID().toString().replace("-", "");
        a.name = name;
        a.createdAt = a.updatedAt = OffsetDateTime.now();
        return a;
    }
    public String getId() { return id; }
    public String getName() { return name; }
}
