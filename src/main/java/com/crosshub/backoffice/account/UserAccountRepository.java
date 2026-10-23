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
}
