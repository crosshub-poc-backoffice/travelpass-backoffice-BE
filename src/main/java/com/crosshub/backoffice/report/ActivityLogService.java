package com.crosshub.backoffice.report;

import com.crosshub.backoffice.account.Caller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리자 메뉴 조회: 작업 이력·로그인 내역·접근 기록·계정 변동 이력·업체 작업 이력. FE lib/server/audit/service.ts 의 조회를 옮긴 것.
 * 기간(from~to)은 일본 시간 날짜. 한 페이지 20건, 최신순.
 * 범위: 작업 이력·업체 이력은 크로스허브 전체 · 연맹 대행 업체 / 로그인·접근·계정 변동은 크로스허브만. 업체 계정은 못 본다.
 * 필터 조합이 많아 JPQL 대신 SQL 을 조건부로 조립한다 (값은 전부 바인딩 파라미터).
 */
@Service
public class ActivityLogService {

    public static final int PAGE_SIZE = 20;
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Set<String> CATEGORIES = Set.of("product", "inquiry", "partner", "account", "scope");
    private static final Set<String> SEARCH_FIELDS = Set.of("loginId", "name", "ip");

    public record Page<T>(List<T> rows, long total) {}

    public record AuditRow(String id, String action, String targetId, String targetName, JsonNode detail, OffsetDateTime createdAt,
                           String partnerName, String userName, String userLoginId, String userRole, String userOrgName) {}

    public record LoginRow(String id, String loginId, String userName, String event, String failReason, String ip, String userAgent,
                           OffsetDateTime createdAt) {}

    public record AccessRow(String id, String menu, String kind, String path, String ip, String userAgent, OffsetDateTime createdAt,
                            String userName, String userLoginId) {}

    public record AccountChangeRow(String id, String action, String targetLoginId, String targetName, String actorName,
                                   String actorLoginId, boolean bySelf, OffsetDateTime createdAt) {}

    public record PartnerHistoryRow(String id, String action, String partnerName, JsonNode detail, String actorName,
                                    String actorLoginId, OffsetDateTime createdAt) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper om;

    public ActivityLogService(NamedParameterJdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    /** SQL 조각 + 파라미터를 같이 쌓는다 */
    private static final class Where {
        final List<String> parts = new ArrayList<>();
        final MapSqlParameterSource p = new MapSqlParameterSource();
        Where and(String sql) { parts.add(sql); return this; }
        String sql() { return parts.isEmpty() ? "" : " where " + String.join(" and ", parts); }
    }

    private static void period(Where w, String alias, String from, String to) {
        LocalDate f = date(from);
        LocalDate t = date(to);
        w.p.addValue("from", f.atStartOfDay(JST).toOffsetDateTime());
        w.p.addValue("to", t.plusDays(1).atStartOfDay(JST).toOffsetDateTime());
        w.and(alias + ".\"createdAt\" >= :from and " + alias + ".\"createdAt\" < :to");
    }

    private static LocalDate date(String v) {
        try {
            return LocalDate.parse(v);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDate");
        }
    }

    private static int offset(int page) {
        return (Math.max(1, page) - 1) * PAGE_SIZE;
    }

    /** 작업 이력 범위: 크로스허브 전체, 연맹 대행 업체, 업체 403 */
    private static void scope(Caller caller, Where w, String alias) {
        if (caller.isCrosshub()) return;
        if (!caller.isAffiliate()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        w.p.addValue("scope", caller.partnerIdsForQuery());
        w.and(alias + ".\"partnerId\" in (:scope)");
    }

    private JsonNode json(String s) {
        if (s == null) return null;
        try {
            return om.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static OffsetDateTime ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    // ── 작업 이력 ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AuditRow> audit(Caller caller, String from, String to, String partnerId, String category, int page) {
        Where w = new Where();
        scope(caller, w, "a");
        period(w, "a", from, to);
        if (partnerId != null && !partnerId.isBlank()) { w.p.addValue("pid", partnerId); w.and("a.\"partnerId\" = :pid"); }
        if (category != null && !category.isBlank()) {
            if (!CATEGORIES.contains(category)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidCategory");
            w.p.addValue("cat", category + "\\_%");
            w.and("a.action like :cat");
        }
        String base = " from \"AuditLog\" a join \"User\" u on u.id = a.\"userId\" left join \"Partner\" pa on pa.id = a.\"partnerId\""
                + " left join \"Affiliate\" ua on ua.id = u.\"affiliateId\" left join \"Partner\" up on up.id = u.\"partnerId\"" + w.sql();
        long total = jdbc.queryForObject("select count(*)" + base, w.p, Long.class);
        w.p.addValue("off", offset(page)).addValue("lim", PAGE_SIZE);
        List<AuditRow> rows = jdbc.query("select a.id, a.action, a.\"targetId\", a.\"targetName\", a.detail::text as detail, a.\"createdAt\","
                + " pa.name as pname, u.name as uname, u.\"loginId\" as ulogin, u.role::text as urole, coalesce(ua.name, up.name) as uorg"
                + base + " order by a.\"createdAt\" desc offset :off limit :lim", w.p,
                (rs, i) -> new AuditRow(rs.getString("id"), rs.getString("action"), rs.getString("targetId"), rs.getString("targetName"),
                        json(rs.getString("detail")), ts(rs.getTimestamp("createdAt")), rs.getString("pname"), rs.getString("uname"),
                        rs.getString("ulogin"), rs.getString("urole"), rs.getString("uorg")));
        return new Page<>(rows, total);
    }

    // ── 로그인 내역 (크로스허브) ──────────────────────────

    /** action: login(성공·실패) | logout, result: success | failed, field: loginId | name | ip, exact: 검색어와 똑같은 것만 */
    @Transactional(readOnly = true)
    public Page<LoginRow> logins(Caller caller, String from, String to, String action, String result, String field, boolean exact,
                                 String keyword, int page) {
        requireCrosshub(caller);
        Where w = new Where();
        period(w, "l", from, to);
        if ("login".equals(action)) w.and("l.event in ('login', 'failed')");
        else if ("logout".equals(action)) w.and("l.event = 'logout'");
        if ("success".equals(result)) w.and("l.event in ('login', 'logout')");
        else if ("failed".equals(result)) w.and("l.event = 'failed'");
        if (keyword != null && !keyword.isBlank()) {
            String col = switch (SEARCH_FIELDS.contains(field) ? field : "loginId") {
                case "name" -> "u.name";
                case "ip" -> "l.ip";
                default -> "l.\"loginId\"";
            };
            if (exact) { w.p.addValue("kw", keyword); w.and(col + " = :kw"); }
            else { w.p.addValue("kw", "%" + keyword.toLowerCase() + "%"); w.and("lower(" + col + ") like :kw"); }
        }
        String base = " from \"LoginLog\" l left join \"User\" u on u.id = l.\"userId\"" + w.sql();
        long total = jdbc.queryForObject("select count(*)" + base, w.p, Long.class);
        w.p.addValue("off", offset(page)).addValue("lim", PAGE_SIZE);
        List<LoginRow> rows = jdbc.query("select l.id, l.\"loginId\", u.name as uname, l.event::text as event, l.\"failReason\", l.ip, l.\"userAgent\", l.\"createdAt\""
                + base + " order by l.\"createdAt\" desc offset :off limit :lim", w.p,
                (rs, i) -> new LoginRow(rs.getString("id"), rs.getString("loginId"), rs.getString("uname"), rs.getString("event"),
                        rs.getString("failReason"), rs.getString("ip"), rs.getString("userAgent"), ts(rs.getTimestamp("createdAt"))));
        return new Page<>(rows, total);
    }

    // ── 접근 기록 (크로스허브) ────────────────────────────

    @Transactional(readOnly = true)
    public Page<AccessRow> access(Caller caller, String from, String to, String menu, String kind, String keyword, int page) {
        requireCrosshub(caller);
        Where w = new Where();
        period(w, "x", from, to);
        if (menu != null && !menu.isBlank()) { w.p.addValue("menu", menu); w.and("x.menu = :menu"); }
        if (kind != null && !kind.isBlank()) { w.p.addValue("kind", kind); w.and("x.kind = :kind"); }
        if (keyword != null && !keyword.isBlank()) {
            w.p.addValue("kw", "%" + keyword.toLowerCase() + "%");
            w.and("(lower(u.\"loginId\") like :kw or lower(u.name) like :kw)");
        }
        String base = " from \"AccessLog\" x join \"User\" u on u.id = x.\"userId\"" + w.sql();
        long total = jdbc.queryForObject("select count(*)" + base, w.p, Long.class);
        w.p.addValue("off", offset(page)).addValue("lim", PAGE_SIZE);
        List<AccessRow> rows = jdbc.query("select x.id, x.menu, x.kind, x.path, x.ip, x.\"userAgent\", x.\"createdAt\", u.name as uname, u.\"loginId\" as ulogin"
                + base + " order by x.\"createdAt\" desc offset :off limit :lim", w.p,
                (rs, i) -> new AccessRow(rs.getString("id"), rs.getString("menu"), rs.getString("kind"), rs.getString("path"),
                        rs.getString("ip"), rs.getString("userAgent"), ts(rs.getTimestamp("createdAt")), rs.getString("uname"), rs.getString("ulogin")));
        return new Page<>(rows, total);
    }

    // ── 계정 변동 이력 (크로스허브) ───────────────────────

    /** 대상 계정 기준(이름·로그인 ID 검색). 발급·비밀번호·활성 상태 변동 */
    @Transactional(readOnly = true)
    public Page<AccountChangeRow> accountChanges(Caller caller, String from, String to, String action, String keyword, int page) {
        requireCrosshub(caller);
        Where w = new Where();
        period(w, "a", from, to);
        if (action != null && action.startsWith("account_")) { w.p.addValue("act", action); w.and("a.action = :act"); }
        else w.and("a.action like 'account\\_%'");
        if (keyword != null && !keyword.isBlank()) {
            w.p.addValue("kw", "%" + keyword.toLowerCase() + "%");
            w.and("a.\"targetId\" in (select id from \"User\" where lower(\"loginId\") like :kw or lower(name) like :kw)");
        }
        String base = " from \"AuditLog\" a join \"User\" u on u.id = a.\"userId\" left join \"User\" t on t.id = a.\"targetId\"" + w.sql();
        long total = jdbc.queryForObject("select count(*)" + base, w.p, Long.class);
        w.p.addValue("off", offset(page)).addValue("lim", PAGE_SIZE);
        List<AccountChangeRow> rows = jdbc.query("select a.id, a.action, a.\"targetName\", t.name as tname, u.name as uname, u.\"loginId\" as ulogin,"
                + " (a.\"userId\" = a.\"targetId\") as byself, a.\"createdAt\"" + base + " order by a.\"createdAt\" desc offset :off limit :lim", w.p,
                (rs, i) -> new AccountChangeRow(rs.getString("id"), rs.getString("action"), rs.getString("targetName"), rs.getString("tname"),
                        rs.getString("uname"), rs.getString("ulogin"), rs.getBoolean("byself"), ts(rs.getTimestamp("createdAt"))));
        return new Page<>(rows, total);
    }

    // ── 업체 작업 이력 (크로스허브 전체 · 연맹 대행 업체) ──

    @Transactional(readOnly = true)
    public Page<PartnerHistoryRow> partnerHistory(Caller caller, String from, String to, String partnerId, String action, int page) {
        Where w = new Where();
        scope(caller, w, "a");
        period(w, "a", from, to);
        if (action != null && action.startsWith("partner_")) { w.p.addValue("act", action); w.and("a.action = :act"); }
        else w.and("a.action like 'partner\\_%'");
        if (partnerId != null && !partnerId.isBlank()) { w.p.addValue("pid", partnerId); w.and("a.\"partnerId\" = :pid"); }
        String base = " from \"AuditLog\" a join \"User\" u on u.id = a.\"userId\" left join \"Partner\" pa on pa.id = a.\"partnerId\"" + w.sql();
        long total = jdbc.queryForObject("select count(*)" + base, w.p, Long.class);
        w.p.addValue("off", offset(page)).addValue("lim", PAGE_SIZE);
        List<PartnerHistoryRow> rows = jdbc.query("select a.id, a.action, coalesce(pa.name, a.\"targetName\", '-') as pname, a.detail::text as detail,"
                + " u.name as uname, u.\"loginId\" as ulogin, a.\"createdAt\"" + base + " order by a.\"createdAt\" desc offset :off limit :lim", w.p,
                (rs, i) -> new PartnerHistoryRow(rs.getString("id"), rs.getString("action"), rs.getString("pname"), json(rs.getString("detail")),
                        rs.getString("uname"), rs.getString("ulogin"), ts(rs.getTimestamp("createdAt"))));
        return new Page<>(rows, total);
    }

    private static void requireCrosshub(Caller caller) {
        if (!caller.isCrosshub()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
}
