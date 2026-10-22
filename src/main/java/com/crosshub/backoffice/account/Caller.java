package com.crosshub.backoffice.account;

/**
 * 현재 요청을 보낸 사람의 "업무상 신원". Keycloak 토큰 + User 테이블 조회로 만든다.
 * - userId: v1 User.id (알림 등 사용자 단위 데이터의 키)
 * - partnerId: 업체 소속이면 그 업체 id (crosshub 운영자는 null)
 * - crosshub(=운영자/관리자)면 모든 업체 접근 가능
 */
public record Caller(String userId, String loginId, String role, String partnerId) {

    public boolean isCrosshub() {
        return "crosshub".equals(role);
    }

    /** 이 업체(partnerId)에 접근할 수 있는가. crosshub는 전부, partner는 자기 것만. */
    public boolean canAccessPartner(String targetPartnerId) {
        if (isCrosshub()) return true;
        return partnerId != null && partnerId.equals(targetPartnerId);
    }
}
