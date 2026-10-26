package com.crosshub.backoffice.product.change;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductChangeRequestRepository extends JpaRepository<ProductChangeRequest, String> {
    boolean existsByProductIdAndStatus(String productId, String status);
    java.util.Optional<ProductChangeRequest> findFirstByProductIdAndStatus(String productId, String status);

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
