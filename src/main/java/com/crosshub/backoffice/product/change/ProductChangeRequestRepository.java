package com.crosshub.backoffice.product.change;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductChangeRequestRepository extends JpaRepository<ProductChangeRequest, String> {
    boolean existsByProductIdAndStatus(String productId, String status);

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

    /** 승인 반영(상품 수정·삭제·상태)이 실패했을 때 되돌린다. 그 승인자가 승인한 상태일 때만 대기중으로 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        update ProductChangeRequest r set r.status = 'pending', r.resolvedById = null, r.resolvedAt = null
        where r.id = :id and r.status = 'approved' and r.resolvedById = :uid
        """)
    int reopen(@org.springframework.data.repository.query.Param("id") String id,
               @org.springframework.data.repository.query.Param("uid") String uid);
}
