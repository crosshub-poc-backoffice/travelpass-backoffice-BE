package com.crosshub.backoffice.report;

import com.crosshub.backoffice.account.CallerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조회 전용 API.
 *  GET /api/v1/activity/audit?from=&to=&partnerId=&category=&page=                      작업 이력 (크로스허브·연맹)
 *  GET /api/v1/activity/logins?from=&to=&action=&result=&field=&exact=&keyword=&page=   로그인 내역 (크로스허브)
 *  GET /api/v1/activity/access?from=&to=&menu=&kind=&keyword=&page=                     접근 기록 (크로스허브)
 *  GET /api/v1/activity/account-changes?from=&to=&action=&keyword=&page=                계정 변동 이력 (크로스허브)
 *  GET /api/v1/activity/partner-history?from=&to=&partnerId=&action=&page=              업체 작업 이력 (크로스허브·연맹)
 */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private final ActivityLogService logs;
    private final CallerService callers;

    public ReportController(ActivityLogService logs, CallerService callers) {
        this.logs = logs;
        this.callers = callers;
    }

    @GetMapping("/activity/audit")
    public ActivityLogService.Page<ActivityLogService.AuditRow> audit(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page) {
        return logs.audit(callers.current(), from, to, partnerId, category, page);
    }

    @GetMapping("/activity/logins")
    public ActivityLogService.Page<ActivityLogService.LoginRow> logins(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String action, @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "loginId") String field, @RequestParam(defaultValue = "false") boolean exact,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page) {
        return logs.logins(callers.current(), from, to, action, result, field, exact, keyword, page);
    }

    @GetMapping("/activity/access")
    public ActivityLogService.Page<ActivityLogService.AccessRow> access(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String menu, @RequestParam(required = false) String kind,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page) {
        return logs.access(callers.current(), from, to, menu, kind, keyword, page);
    }

    @GetMapping("/activity/account-changes")
    public ActivityLogService.Page<ActivityLogService.AccountChangeRow> accountChanges(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String action, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page) {
        return logs.accountChanges(callers.current(), from, to, action, keyword, page);
    }

    @GetMapping("/activity/partner-history")
    public ActivityLogService.Page<ActivityLogService.PartnerHistoryRow> partnerHistory(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page) {
        return logs.partnerHistory(callers.current(), from, to, partnerId, action, page);
    }
}
