package com.crosshub.backoffice.inquiry;

import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository extends JpaRepository<Inquiry, String> {

    // ----- 고객용 API (customerId = 고객 토큰 sub)
    java.util.List<Inquiry> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    java.util.Optional<Inquiry> findByIdAndCustomerId(String id, String customerId);
    java.util.Optional<Inquiry> findByIdempotencyKey(String idempotencyKey);


    /** 모든 선택 필터를 boolean 플래그로 — null 파라미터 금지(Postgres 타입 추론 실패 방지). */
    @Query("""
        select i from Inquiry i
        where (:allPartners = true or i.product.partnerId in :partnerIds)
          and (:anyStatus = true or i.status = :status)
          and (:noKeyword = true
               or lower(i.id) like :kw
               or lower(i.customerName) like :kw
               or lower(i.product.name) like :kw
               or lower(i.question) like :kw
               or lower(coalesce(i.questionTranslated, '')) like :kw
               or i.productId in :translatedIds)
        """)
    Page<Inquiry> search(
            @Param("allPartners") boolean allPartners,
            @Param("partnerIds") Collection<String> partnerIds,
            @Param("anyStatus") boolean anyStatus,
            @Param("status") String status,
            @Param("noKeyword") boolean noKeyword,
            @Param("kw") String kw,
            @Param("translatedIds") Collection<String> translatedIds,
            Pageable pageable);
}
