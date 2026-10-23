package com.crosshub.backoffice.booking;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, String> {
    List<Payment> findByBookingId(String bookingId);

    /**
     * 결제내역: [Payment, Booking.productId, 상품명, 업체명, 업체 통화]. 업체는 상품 기준(FE와 같음).
     * null 파라미터를 안 보내려고 선택 필터는 boolean 플래그로.
     */
    @org.springframework.data.jpa.repository.Query(value = """
        select p, b.productId, pr.name, pa.name, pa.currency
        from Payment p, Booking b, com.crosshub.backoffice.product.Product pr, com.crosshub.backoffice.partner.Partner pa
        where b.id = p.bookingId and pr.id = b.productId and pa.id = pr.partnerId
          and (:allPartners = true or pr.partnerId in :partnerIds)
          and p.createdAt >= :from and p.createdAt < :to
          and (:anyType = true or p.type = :type)
        order by p.createdAt desc
        """, countQuery = """
        select count(p)
        from Payment p, Booking b, com.crosshub.backoffice.product.Product pr
        where b.id = p.bookingId and pr.id = b.productId
          and (:allPartners = true or pr.partnerId in :partnerIds)
          and p.createdAt >= :from and p.createdAt < :to
          and (:anyType = true or p.type = :type)
        """)
    org.springframework.data.domain.Page<Object[]> search(
            @org.springframework.data.repository.query.Param("allPartners") boolean allPartners,
            @org.springframework.data.repository.query.Param("partnerIds") java.util.Collection<String> partnerIds,
            @org.springframework.data.repository.query.Param("from") java.time.OffsetDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.OffsetDateTime to,
            @org.springframework.data.repository.query.Param("anyType") boolean anyType,
            @org.springframework.data.repository.query.Param("type") String type,
            org.springframework.data.domain.Pageable pageable);
}
