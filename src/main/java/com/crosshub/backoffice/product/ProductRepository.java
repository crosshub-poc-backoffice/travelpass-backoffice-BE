package com.crosshub.backoffice.product;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, String> {
    /** 상품 행 잠금(SELECT ... FOR UPDATE). 같은 상품에 대한 변경요청 생성을 한 줄로 세워 대기 요청이 2개 생기지 않게 한다 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Product p where p.id = :id")
    java.util.Optional<Product> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);

    List<Product> findByPartnerId(String partnerId);
    List<Product> findByStatus(String status);
    List<Product> findByPartnerIdAndStatus(String partnerId, String status);
    List<Product> findByPartnerIdIn(Collection<String> partnerIds);
    List<Product> findByPartnerIdInAndStatus(Collection<String> partnerIds, String status);
    long countByPartnerIdAndHiddenByPartnerOffTrue(String partnerId);
    List<Product> findByPartnerIdAndHiddenByPartnerOffTrue(String partnerId);
    List<Product> findByPartnerIdAndStatusAndHiddenByPartnerOffTrue(String partnerId, String status);
}
