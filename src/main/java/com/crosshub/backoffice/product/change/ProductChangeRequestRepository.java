package com.crosshub.backoffice.product.change;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductChangeRequestRepository extends JpaRepository<ProductChangeRequest, String> {
    boolean existsByProductIdAndStatus(String productId, String status);
}
