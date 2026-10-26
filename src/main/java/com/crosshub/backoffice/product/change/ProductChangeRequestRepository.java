package com.crosshub.backoffice.product.change;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductChangeRequestRepository extends JpaRepository<ProductChangeRequest, String> {
    boolean existsByProductIdAndStatus(String productId, String status);
    java.util.Optional<ProductChangeRequest> findFirstByProductIdAndStatus(String productId, String status);
    /** 상품들의 대기중·반려 요청 (목록 뱃지·상세 배너용). 최신순 — 호출부가 상품마다 첫 건만 쓴다 */
    java.util.List<ProductChangeRequest> findByProductIdInAndStatusInOrderByCreatedAtDesc(java.util.Collection<String> productIds, java.util.Collection<String> statuses);
    /** 대시보드: 범위 안 업체 상품의 대기중 요청, 오래 기다린 순 */
    @org.springframework.data.jpa.repository.Query("""
        select r from ProductChangeRequest r, Product p
        where p.id = r.productId and r.status = 'pending' and (:allPartners = true or p.partnerId in :partnerIds)
        order by r.createdAt asc
        """)
    org.springframework.data.domain.Page<ProductChangeRequest> findPending(
            @org.springframework.data.repository.query.Param("allPartners") boolean allPartners,
            @org.springframework.data.repository.query.Param("partnerIds") java.util.Collection<String> partnerIds,
            org.springframework.data.domain.Pageable pageable);

    /** 대기중일 때만 승인으로 바꾼다. 바뀐 행 수(0이면 이미 누가 처리함) — 동시 승인·승인/반려 경합 방지 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        update ProductChangeRequest r set r.status = 'approved', r.resolvedById = :uid, r.resolvedAt = :now
        where r.id = :id and r.status = 'pending'
        """)
    int markApproved(@org.springframework.data.repository.query.Param("id") String id,
                     @org.springframework.data.repository.query.Param("uid") String uid,
                     @org.springframework.data.repository.query.Param("now") java.time.OffsetDateTime now);

    /** 대기중일 때만 반려로 바꾼다 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        update ProductChangeRequest r set r.status = 'rejected', r.resolvedById = :uid, r.resolvedAt = :now, r.rejectReason = :reason
        where r.id = :id and r.status = 'pending'
        """)
    int markRejected(@org.springframework.data.repository.query.Param("id") String id,
                     @org.springframework.data.repository.query.Param("uid") String uid,
                     @org.springframework.data.repository.query.Param("now") java.time.OffsetDateTime now,
                     @org.springframework.data.repository.query.Param("reason") String reason);
}
