package com.crosshub.backoffice.product.translation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductTranslationRepository extends JpaRepository<ProductTranslation, String> {
    List<ProductTranslation> findByProductId(String productId);
    Optional<ProductTranslation> findByProductIdAndLocale(String productId, String locale);
    void deleteByProductIdAndLocale(String productId, String locale);
    List<ProductTranslation> findByProductIdInAndLocale(java.util.Collection<String> productIds, String locale);
    List<ProductTranslation> findByProductIdIn(java.util.Collection<String> productIds);

    /** 어느 언어든 번역된 상품명에 keyword 가 들어 있는 상품 (목록 검색용). kw = '%' + lower(keyword) + '%' */
    @org.springframework.data.jpa.repository.Query(value = "select distinct \"productId\" from \"ProductTranslation\" where lower(texts->>'name') like :kw", nativeQuery = true)
    List<String> findProductIdsByTranslatedName(@org.springframework.data.repository.query.Param("kw") String kw);
}
