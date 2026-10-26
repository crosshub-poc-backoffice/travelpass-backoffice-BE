package com.crosshub.backoffice.booking;

import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, String> {

    // ----- 고객용 API (customerId = 고객 토큰 sub)
    java.util.List<Booking> findByCustomerIdOrderByUseDateDescRequestedAtDesc(String customerId);
    java.util.Optional<Booking> findByIdAndCustomerId(String id, String customerId);
    java.util.Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /** 그 날 그 상품에 잡힌 인원 합 (거절·취소·환불·철회 제외). 정원 판정용 */
    @Query("""
        select coalesce(sum(b.people), 0) from Booking b
        where b.productId = :productId and b.useDate = :useDate
          and b.status not in ('rejected', 'canceled', 'refunded', 'withdrawn')
        """)
    long sumPeopleOn(@Param("productId") String productId, @Param("useDate") java.time.LocalDate useDate);


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

    /** 이 업체 상품에 예약이 하나라도 있는가 (통화·운영방식 잠금 판단용, FE의 _count.products where bookings some) */
    @Query("select count(b) > 0 from Booking b where b.productId in (select p.id from Product p where p.partnerId = :partnerId)")
    boolean existsForPartner(@org.springframework.data.repository.query.Param("partnerId") String partnerId);

    /** 정산 대상 예약: 업체들의 이용일 [start, end) 범위, 이용완료·취소·환불. 상품명까지 같이 읽는다 */
    @Query("""
        select b from Booking b join fetch b.product
        where b.partnerId in :partnerIds
          and b.status in ('completed', 'canceled', 'refunded')
          and b.useDate >= :start and b.useDate < :end
        order by b.useDate asc, b.id asc
        """)
    java.util.List<Booking> findForSettlement(@Param("partnerIds") Collection<String> partnerIds,
                                             @Param("start") java.time.LocalDate start,
                                             @Param("end") java.time.LocalDate end);

    /** 그 달 이용일인데 아직 확정(이용완료 전)인 예약 수 — 있으면 지급완료로 굳히면 안 된다 */
    @Query("""
        select count(b) from Booking b
        where b.partnerId in :partnerIds and b.status = 'confirmed'
          and b.useDate >= :start and b.useDate < :end
        """)
    long countUnfinished(@Param("partnerIds") Collection<String> partnerIds,
                         @Param("start") java.time.LocalDate start,
                         @Param("end") java.time.LocalDate end);
}
