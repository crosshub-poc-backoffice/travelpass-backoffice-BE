package com.crosshub.backoffice.account;

import com.crosshub.backoffice.account.dto.AccountResponse;
import com.crosshub.backoffice.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 조회 API (크로스허브 전용 메뉴).
 *  GET /api/v1/accounts?role=&orgId=&status=&keyword=&sort=&page=
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AccountResponse> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page) {
        return service.list(role, orgId, status, keyword, sort, page);
    }
}
