package com.crosshub.backoffice.account;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {

    Optional<UserAccount> findByLoginId(String loginId);
    boolean existsByLoginId(String loginId);
    long countByAffiliateId(String affiliateId);
    long countByPartnerId(String partnerId);

    // 알림 받는 사람 (Notifier). role 은 Postgres enum — 값은 crosshub | affiliate | partner 만 넘긴다
    java.util.List<UserAccount> findByRoleAndActiveTrue(String role);
    java.util.List<UserAccount> findByRoleAndPartnerIdAndActiveTrue(String role, String partnerId);
    java.util.List<UserAccount> findByRoleAndAffiliateIdAndActiveTrue(String role, String affiliateId);

    /**
     * 계정 목록(필터). null 파라미터를 DB에 안 보내려고 전부 boolean 플래그로.
     *  role은 Postgres enum → anyRole=true면 무시(더미는 유효 enum 'crosshub').
     *  status는 active/pending(lastLoginAt 없음) 조합으로 호출부에서 풀어 넘긴다.
     */
    @Query("""
        select u from UserAccount u
        where (:anyRole = true or u.role = :role)
          and (:anyOrg = true or u.affiliateId = :orgId or u.partnerId = :orgId)
          and (:anyActive = true or u.active = :active)
          and (:anyPending = true
               or (:pending = true and u.lastLoginAt is null)
               or (:pending = false and u.lastLoginAt is not null))
          and (:noKeyword = true
               or lower(u.loginId) like :kw
               or lower(u.name) like :kw)
        """)
    Page<UserAccount> search(
            @Param("anyRole") boolean anyRole,
            @Param("role") String role,
            @Param("anyOrg") boolean anyOrg,
            @Param("orgId") String orgId,
            @Param("anyActive") boolean anyActive,
            @Param("active") boolean active,
            @Param("anyPending") boolean anyPending,
            @Param("pending") boolean pending,
            @Param("noKeyword") boolean noKeyword,
            @Param("kw") String kw,
            Pageable pageable);

    /** 업체 계정 이름은 업체명을 그대로 쓴다 — 업체명이 바뀌면 같이 바꾼다 */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update UserAccount u set u.name = :name, u.updatedAt = :now where u.partnerId = :partnerId")
    int renameByPartnerId(@Param("partnerId") String partnerId, @Param("name") String name,
                          @Param("now") java.time.OffsetDateTime now);
}
