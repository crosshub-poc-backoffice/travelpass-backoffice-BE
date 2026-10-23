package com.crosshub.backoffice.product;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, String> {
    List<Product> findByPartnerId(String partnerId);
    List<Product> findByStatus(String status);
    List<Product> findByPartnerIdAndStatus(String partnerId, String status);
    List<Product> findByPartnerIdIn(Collection<String> partnerIds);
    List<Product> findByPartnerIdInAndStatus(Collection<String> partnerIds, String status);
    long countByPartnerIdAndHiddenByPartnerOffTrue(String partnerId);
    List<Product> findByPartnerIdAndHiddenByPartnerOffTrue(String partnerId);
    List<Product> findByPartnerIdAndStatusAndHiddenByPartnerOffTrue(String partnerId, String status);
}
