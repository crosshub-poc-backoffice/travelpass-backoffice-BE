package com.crosshub.backoffice.account;

import com.crosshub.backoffice.account.dto.AccountResponse;
import com.crosshub.backoffice.common.PageResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 계정 조회. 계정 관리는 크로스허브 전용 메뉴라 별도 업체 스코프는 두지 않는다(권한은 SecurityConfig). */
@Service
public class AccountService {

    private static final int PAGE_SIZE = 20;

    private final UserAccountRepository users;
    private final LoginLogRepository loginLogs;

    public AccountService(UserAccountRepository users, LoginLogRepository loginLogs) {
        this.users = users;
        this.loginLogs = loginLogs;
    }

    /**
     * @param role    crosshub|affiliate|partner 또는 null(전체)
     * @param orgId   연맹/업체 id 또는 null(전체)
     * @param status  active|pending|inactive 또는 null(전체)
     * @param keyword loginId/name 검색
     * @param sort    "lastLogin" | "created"
     */
    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> list(
            String role, String orgId, String status, String keyword, String sort, int page) {

        boolean anyRole = (role == null || role.isBlank());
        String roleParam = anyRole ? "crosshub" : role;              // enum 더미는 유효값

        boolean anyOrg = (orgId == null || orgId.isBlank());
        String orgParam = anyOrg ? "_none_" : orgId;

        // status → active / mustChangePassword 조합
        boolean anyActive = true, activeVal = true, anyMustChange = true, mustChangeVal = false;
        if ("active".equals(status)) { anyActive = false; activeVal = true; anyMustChange = false; mustChangeVal = false; }
        else if ("pending".equals(status)) { anyActive = false; activeVal = true; anyMustChange = false; mustChangeVal = true; }
        else if ("inactive".equals(status)) { anyActive = false; activeVal = false; anyMustChange = true; }

        boolean noKeyword = (keyword == null || keyword.isBlank());
        String kw = noKeyword ? "_none_" : "%" + keyword.trim().toLowerCase() + "%";

        Sort sortBy = "lastLogin".equals(sort)
                ? Sort.by(new Sort.Order(Sort.Direction.DESC, "lastLoginAt").nullsLast(),
                          new Sort.Order(Sort.Direction.DESC, "createdAt"))
                : Sort.by(Sort.Direction.DESC, "createdAt");
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), PAGE_SIZE, sortBy);

        Page<UserAccount> result = users.search(
                anyRole, roleParam, anyOrg, orgParam, anyActive, activeVal,
                anyMustChange, mustChangeVal, noKeyword, kw, pageable);

        List<AccountResponse> rows = result.getContent().stream().map(u -> {
            var log = loginLogs.findFirstByUserIdAndEventOrderByCreatedAtDesc(u.getId(), "login").orElse(null);
            return AccountResponse.of(u, log != null ? log.getIp() : null, log != null ? log.getUserAgent() : null);
        }).toList();

        return PageResponse.of(result, rows);
    }
}
