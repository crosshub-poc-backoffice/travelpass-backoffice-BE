package com.crosshub.backoffice.account.dto;

import com.crosshub.backoffice.account.UserAccount;
import java.time.OffsetDateTime;

/** 계정 관리 목록 행. 마지막 접속 IP/브라우저는 LoginLog에서 따로 채운다. */
public record AccountResponse(
        String id,
        String loginId,
        String name,
        String role,
        String orgName,
        boolean mustChangePassword,
        boolean active,
        OffsetDateTime lastLoginAt,
        String lastLoginIp,
        String lastLoginUserAgent,
        OffsetDateTime createdAt) {

    public static AccountResponse of(UserAccount u, String ip, String ua) {
        return new AccountResponse(
                u.getId(), u.getLoginId(), u.getName(), u.getRole(), u.getOrgName(),
                u.isMustChangePassword(), u.isActive(), u.getLastLoginAt(), ip, ua, u.getCreatedAt());
    }
}
