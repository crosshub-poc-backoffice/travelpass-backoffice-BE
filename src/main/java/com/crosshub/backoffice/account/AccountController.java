package com.crosshub.backoffice.account;

import com.crosshub.backoffice.account.dto.AccountResponse;
import com.crosshub.backoffice.common.PageResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 API (크로스허브 전용 메뉴).
 *  GET  /api/v1/accounts?role=&orgId=&status=&keyword=&sort=&page=
 *  POST /api/v1/accounts                         발급 → {id, loginId, partnerId, tempPassword}
 *  POST /api/v1/accounts/{id}/reset-password     임시 비번 초기화 → {loginId, tempPassword}
 *  POST /api/v1/accounts/{id}/toggle-active      활성/비활성 → {active}
 *  실패 응답: {code} (FE 에러 키와 같은 이름: loginIdTaken, orgHasAccount, keycloakSyncFailed ...)
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService service;
    private final AccountCommandService commands;
    private final CallerService callers;

    public AccountController(AccountService service, AccountCommandService commands, CallerService callers) {
        this.service = service;
        this.commands = commands;
        this.callers = callers;
    }

    @GetMapping
    public PageResponse<AccountResponse> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page) {
        callers.requireCrosshub();   // DB role 기준 crosshub 전용
        return service.list(role, orgId, status, keyword, sort, page);
    }

    /** 소속 선택지(연맹·직접 운영 업체)와 이미 계정이 있는지 — 소속당 계정은 1개라 발급 화면에서 뺀다 */
    @GetMapping("/orgs")
    public AccountService.Orgs orgs() {
        callers.requireCrosshub();
        return service.orgs();
    }

    @PostMapping
    public AccountCommandService.Issued issue(@RequestBody AccountCommandService.IssueRequest req) {
        return commands.issue(callers.requireCrosshub(), req);
    }

    @PostMapping("/{id}/reset-password")
    public AccountCommandService.Reset resetPassword(@PathVariable String id) {
        return commands.resetPassword(callers.requireCrosshub(), id);
    }

    @PostMapping("/{id}/toggle-active")
    public Map<String, Boolean> toggleActive(@PathVariable String id) {
        return Map.of("active", commands.toggleActive(callers.requireCrosshub(), id));
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<Map<String, String>> onAccountError(AccountException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }
}
