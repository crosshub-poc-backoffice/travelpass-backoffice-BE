package com.crosshub.backoffice.booking;

import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, String> {

    /**
     * 모든 선택 필터를 boolean 플래그로 처리한다 — null 파라미터를 DB에 보내면
     * (stringtype=unspecified 환경에서) Postgres가 타입을 못 정해 터지므로, 파라미터는 항상 non-null.
     *  allPartners/anyStatus/anyProduct/noKeyword = true면 그 조건 무시.
     *  kw는 '%'+lower(keyword)+'%'.
     */
    @Query("""
        select b from Booking b
        where (:allPartners = true or b.partnerId in :partnerIds)
          and (:anyStatus = true or b.status in :statuses)
          and (:anyProduct = true or b.productId = :productId)
          and (:noKeyword = true
               or lower(b.id) like :kw
               or lower(b.customerName) like :kw
               or lower(b.product.name) like :kw)
        """)
    Page<Booking> search(
            @Param("allPartners") boolean allPartners,
            @Param("partnerIds") Collection<String> partnerIds,
            @Param("anyStatus") boolean anyStatus,
            @Param("statuses") Collection<String> statuses,
            @Param("anyProduct") boolean anyProduct,
            @Param("productId") String productId,
            @Param("noKeyword") boolean noKeyword,
            @Param("kw") String kw,
            Pageable pageable);
}
