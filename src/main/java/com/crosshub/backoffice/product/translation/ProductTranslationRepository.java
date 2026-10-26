package com.crosshub.backoffice.product.translation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductTranslationRepository extends JpaRepository<ProductTranslation, String> {
    List<ProductTranslation> findByProductId(String productId);
    Optional<ProductTranslation> findByProductIdAndLocale(String productId, String locale);
    void deleteByProductIdAndLocale(String productId, String locale);
}
