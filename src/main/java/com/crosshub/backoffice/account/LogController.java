package com.crosshub.backoffice.account;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 로그 기록 API. FE(BFF)가 부른다 — FE 는 DB 에 직접 쓰지 않는다.
 *  POST /api/v1/logs/login   { event: login|logout|failed, failReason?, ip?, userAgent? }
 *       누구인지는 토큰의 preferred_username 으로 정한다(본문으로 받지 않는다). 백오피스에 없는 계정(failed/unknownId)도 기록한다.
 *  POST /api/v1/logs/access  { entries: [{ menu, kind, path? }], ip?, userAgent? }   요청 한 번의 메뉴별 접근 기록 (호출자 본인)
 * ip·userAgent 는 FE 가 받은 브라우저 요청의 값이다 (BE 가 보는 건 FE 서버 주소라). 길이만 자른다.
 */
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {

    static final Set<String> EVENTS = Set.of("login", "logout", "failed");
    static final Set<String> KINDS = Set.of("list", "view", "create", "update", "delete");
    static final int MAX_ENTRIES = 20;

    public record LoginRequest(String event, String failReason, String ip, String userAgent) {}
    public record AccessEntry(String menu, String kind, String path) {}
    public record AccessRequest(List<AccessEntry> entries, String ip, String userAgent) {}

    private final LoginLogRepository loginLogs;
    private final AccessLogRepository accessLogs;
    private final UserAccountRepository users;
    private final CallerService callers;
    private final boolean devEnabled;

    public LogController(LoginLogRepository loginLogs, AccessLogRepository accessLogs, UserAccountRepository users,
                         CallerService callers, @Value("${app.auth.disabled:false}") boolean devEnabled) {
        this.loginLogs = loginLogs;
        this.accessLogs = accessLogs;
        this.users = users;
        this.callers = callers;
        this.devEnabled = devEnabled;
    }

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<Void> login(@RequestBody LoginRequest req) {
        if (req.event() == null || !EVENTS.contains(req.event())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidEvent");
        String loginId = currentLoginId();
        String userId = users.findByLoginId(loginId).map(UserAccount::getId).orElse(null);
        String failReason = "failed".equals(req.event()) ? cut(req.failReason(), 40) : null;
        loginLogs.save(LoginLog.of(userId, cut(loginId, 100), req.event(), failReason, cut(req.ip(), 64), cut(req.userAgent(), 300)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/access")
    @Transactional
    public ResponseEntity<Void> access(@RequestBody AccessRequest req) {
        Caller caller = callers.current();
        if (req.entries() == null || req.entries().isEmpty()) return ResponseEntity.noContent().build();
        if (req.entries().size() > MAX_ENTRIES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tooMany");
        for (AccessEntry e : req.entries()) {
            if (e.menu() == null || e.menu().isBlank() || e.menu().length() > 40 || e.kind() == null || !KINDS.contains(e.kind())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidEntry");
            }
            accessLogs.save(AccessLog.of(caller.userId(), e.menu(), e.kind(), cut(e.path(), 300), cut(req.ip(), 64), cut(req.userAgent(), 300)));
        }
        return ResponseEntity.noContent().build();
    }

    /** 토큰의 preferred_username. 고객 토큰은 거절. dev 모드(토큰 없음)면 dev 사용자 */
    private String currentLoginId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            if (auth.getAuthorities().stream().anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            }
            String username = jwt.getClaimAsString("preferred_username");
            if (username == null || username.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
            return username;
        }
        if (devEnabled) return callers.current().loginId();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
