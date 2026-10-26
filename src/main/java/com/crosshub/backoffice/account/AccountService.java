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

    public record Org(String id, String name, boolean hasAccount) {}
    public record Orgs(List<Org> affiliates, List<Org> partners) {}

    /** 소속 선택지. 업체 계정은 직접 운영 업체(proxyAffiliateId 없음)에만 준다 (대행 업체는 연맹이 처리) */
    @Transactional(readOnly = true)
    public Orgs orgs() {
        List<Org> affiliates = affiliateRepo.findAll(Sort.by("name")).stream()
                .map(a -> new Org(a.getId(), a.getName(), users.countByAffiliateId(a.getId()) > 0)).toList();
        List<Org> partners = partnerRepo.findByProxyAffiliateIdIsNullOrderByNameAsc().stream()
                .map(p -> new Org(p.getId(), p.getName(), users.countByPartnerId(p.getId()) > 0)).toList();
        return new Orgs(affiliates, partners);
    }

    private final com.crosshub.backoffice.partner.AffiliateRepository affiliateRepo;
    private final com.crosshub.backoffice.partner.PartnerRepository partnerRepo;

    public AccountService(UserAccountRepository users, LoginLogRepository loginLogs,
                          com.crosshub.backoffice.partner.AffiliateRepository affiliateRepo,
                          com.crosshub.backoffice.partner.PartnerRepository partnerRepo) {
        this.affiliateRepo = affiliateRepo;
        this.partnerRepo = partnerRepo;
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

        // status → active / pending 조합. pending = 발급 후 아직 로그인 안 함(lastLoginAt 없음)
        boolean anyActive = true, activeVal = true, anyPending = true, pendingVal = false;
        if ("active".equals(status)) { anyActive = false; activeVal = true; anyPending = false; pendingVal = false; }
        else if ("pending".equals(status)) { anyActive = false; activeVal = true; anyPending = false; pendingVal = true; }
        else if ("inactive".equals(status)) { anyActive = false; activeVal = false; anyPending = true; }

        boolean noKeyword = (keyword == null || keyword.isBlank());
        String kw = noKeyword ? "_none_" : "%" + keyword.trim().toLowerCase() + "%";

        Sort sortBy = "lastLogin".equals(sort)
                ? Sort.by(new Sort.Order(Sort.Direction.DESC, "lastLoginAt").nullsLast(),
                          new Sort.Order(Sort.Direction.DESC, "createdAt"))
                : Sort.by(Sort.Direction.DESC, "createdAt");
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), PAGE_SIZE, sortBy);

        Page<UserAccount> result = users.search(
                anyRole, roleParam, anyOrg, orgParam, anyActive, activeVal,
                anyPending, pendingVal, noKeyword, kw, pageable);

        List<AccountResponse> rows = result.getContent().stream().map(u -> {
            var log = loginLogs.findFirstByUserIdAndEventOrderByCreatedAtDesc(u.getId(), "login").orElse(null);
            return AccountResponse.of(u, log != null ? log.getIp() : null, log != null ? log.getUserAgent() : null);
        }).toList();

        return PageResponse.of(result, rows);
    }
}
