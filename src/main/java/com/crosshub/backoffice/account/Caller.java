package com.crosshub.backoffice.account;

import java.util.List;

/**
 * 현재 요청을 보낸 사람의 "업무상 신원". Keycloak 토큰(preferred_username) → User 테이블 조회로 만든다.
 * 권한 판단 기준은 DB(User.role)다. 토큰 role은 SecurityConfig에서 "API 호출 가능 여부" 1차 거름망으로만 쓴다.
 *
 * - role: crosshub | affiliate | partner (Prisma Role enum, Keycloak client role 이름과 동일)
 * - partnerIds: 이 사람이 다룰 수 있는 업체 id 목록
 *     crosshub = 사용 안 함(전체), affiliate = 대행 업체 전체(Partner.proxyAffiliateId), partner = 자기 업체 1개
 */
public record Caller(String userId, String loginId, String role, String partnerId, String affiliateId,
                     List<String> partnerIds) {

    public Caller {
        partnerIds = partnerIds == null ? List.of() : List.copyOf(partnerIds);
    }

    public boolean isCrosshub() {
        return "crosshub".equals(role);
    }

    public boolean isAffiliate() {
        return "affiliate".equals(role);
    }

    /** 이 업체(partnerId)에 접근할 수 있는가. crosshub는 전부, affiliate는 대행 업체, partner는 자기 것만. */
    public boolean canAccessPartner(String targetPartnerId) {
        if (isCrosshub()) return true;
        return targetPartnerId != null && partnerIds.contains(targetPartnerId);
    }

    /**
     * JPQL `in :partnerIds`에 넣을 목록. 빈 목록은 DB에 따라 `in ()` 문법 오류가 나서 절대 안 맞는 더미로 대신한다.
     * crosshub는 호출 쪽에서 allPartners=true로 처리하므로 여기 값은 쓰이지 않는다.
     */
    public List<String> partnerIdsForQuery() {
        return partnerIds.isEmpty() ? List.of("_none_") : partnerIds;
    }
}
