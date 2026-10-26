package com.crosshub.backoffice.product.translation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * v1 "ProductTranslation" — 상품 한 언어의 번역. texts = 키 → 번역문, sources = 키 → 번역할 때의 원문.
 * (productId, locale) 유니크. reviewed = 사람이 검수 완료. 키 규칙은 ProductTexts.
 */
@Entity
@Table(name = "ProductTranslation")
public class ProductTranslation {

    @Id @Column(name = "id") private String id;
    @Column(name = "productId") private String productId;
    @Column(name = "locale") private String locale;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "texts") private String texts;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "sources") private String sources;
    @Column(name = "reviewed") private boolean reviewed;
    @Column(name = "updatedAt") private OffsetDateTime updatedAt;

    protected ProductTranslation() {}

    public static ProductTranslation of(String productId, String locale, String textsJson, String sourcesJson, boolean reviewed) {
        ProductTranslation t = new ProductTranslation();
        t.id = UUID.randomUUID().toString().replace("-", "");
        t.productId = productId;
        t.locale = locale;
        t.update(textsJson, sourcesJson, reviewed);
        return t;
    }

    public void update(String textsJson, String sourcesJson, boolean reviewed) {
        this.texts = textsJson;
        this.sources = sourcesJson;
        this.reviewed = reviewed;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getProductId() { return productId; }
    public String getLocale() { return locale; }
    public String getTexts() { return texts; }
    public String getSources() { return sources; }
    public boolean isReviewed() { return reviewed; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
