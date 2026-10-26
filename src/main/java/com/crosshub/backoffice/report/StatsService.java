package com.crosshub.backoffice.report;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.product.translation.ProductTranslationReadService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 판매 통계. FE lib/server/stats/service.ts 를 1:1 로 옮긴 것 — 숫자 하나도 달라지면 안 돼서 계산 순서까지 그대로 둔다.
 *
 * 기간(from~to, 일본 시간)은 예약 접수일 기준이고 판매 = 확정·이용완료 예약. 카드·추이·표·수익이 모두 같은 기준.
 * 수익은 그 판매 건들의 계정별 몫 (예약 때 박힌 요율): 업체 = 정산액, 연맹 = 연맹 몫, 크로스허브 = 수수료 − PG − 연맹 몫.
 * 증감은 기간이 걸친 달 수만큼 앞의 같은 날짜와 비교. 범위는 로그인 계정이 다룰 수 있는 업체 (partnerId 를 주면 그 업체만).
 * 금액은 통화별로 따로 더한다 (환산 없음).
 */
@Service
public class StatsService {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Set<String> SOLD = Set.of("confirmed", "completed");

    public record Range(String from, String to) {}
    public record Top(String name, boolean nameAi, long count) {}
    public record Bucket(String key, Map<String, Long> amount, Map<String, Long> sold, Map<String, List<Top>> top) {}
    public record Money(String currency, long amount, long prevAmount) {}
    public record Settlement(List<Money> rows) {}
    public record ProductStat(String id, String name, boolean nameAi, String partner, String currency, long sold, long canceled, long amount) {}
    public record PartnerStat(String id, String name, String currency, long sold, long canceled, long amount, long settlementNet) {}

    /** FE StatsData 와 같은 모양 */
    public record StatsData(Range prev, String bucket, List<Bucket> series, long soldTotal, long prevSoldTotal, long canceledTotal,
                            List<Money> sales, Settlement settlement, List<ProductStat> products, List<PartnerStat> partners) {}

    private record SoldRow(String day, String currency, String partnerId, String productId, long count, long amount, long net,
                           long commission, double affiliate, double pg) {}

    private record Group(String productId, String status, long count, long amount) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final PartnerRepository partners;
    private final ProductTranslationReadService names;

    public StatsService(NamedParameterJdbcTemplate jdbc, PartnerRepository partners, ProductTranslationReadService names) {
        this.jdbc = jdbc;
        this.partners = partners;
        this.names = names;
    }

    static String lastDayOf(String month) {
        return YearMonth.parse(month).atEndOfMonth().toString();
    }

    /** 같은 날짜를 n달 전으로. 그 달에 없는 날(31일 등)·말일은 그 달 말일로 */
    static String dayMonthsBefore(String day, int n) {
        String month = YearMonth.parse(day.substring(0, 7)).minusMonths(n).toString();
        String last = lastDayOf(month);
        String same = month + "-" + day.substring(8);
        return day.equals(lastDayOf(day.substring(0, 7))) || same.compareTo(last) > 0 ? last : same;
    }

    /** 증감 비교 기간: 기간이 걸친 달 수만큼 앞의 같은 날짜 (10/1~15 → 9/1~15, 5/1~10/15 → 11/1~4/15) */
    static Range previousRange(String from, String to) {
        int months = (Integer.parseInt(to.substring(0, 4)) - Integer.parseInt(from.substring(0, 4))) * 12
                + Integer.parseInt(to.substring(5, 7)) - Integer.parseInt(from.substring(5, 7)) + 1;
        return new Range(dayMonthsBefore(from, months), dayMonthsBefore(to, months));
    }

    @Transactional(readOnly = true)
    public StatsData stats(Caller caller, String from, String to, String partnerId, String locale) {
        LocalDate f;
        LocalDate t;
        try {
            f = LocalDate.parse(from);
            t = LocalDate.parse(to);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDate");
        }
        if (t.isBefore(f)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDate");

        // 범위: 크로스허브 전체, 그 외는 관리 범위. partnerId 를 주면 범위 안일 때만 그 업체 하나
        List<Partner> scope = caller.isCrosshub() ? partners.findAll() : partners.findAllById(caller.partnerIds());
        if (partnerId != null && !partnerId.isBlank()) {
            if (!caller.canAccessPartner(partnerId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notFound");
            scope = scope.stream().filter(p -> p.getId().equals(partnerId)).toList();
        }
        List<Partner> scoped = new ArrayList<>(scope);
        scoped.sort(Comparator.comparing(Partner::getName));
        List<String> partnerIds = scoped.stream().map(Partner::getId).toList();
        Map<String, Partner> partnerById = new HashMap<>();
        for (Partner p : scoped) partnerById.put(p.getId(), p);

        long days = ChronoUnit.DAYS.between(f, t) + 1;
        Range prev = previousRange(from, to);

        List<SoldRow> soldRows = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        if (!partnerIds.isEmpty()) {
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("pids", partnerIds)
                    .addValue("prevFrom", LocalDate.parse(prev.from()).atStartOfDay(JST).toOffsetDateTime())
                    .addValue("from", f.atStartOfDay(JST).toOffsetDateTime())
                    .addValue("end", t.plusDays(1).atStartOfDay(JST).toOffsetDateTime());
            // 판매는 날짜(일본 시간)·상품별로 합산해 결과 행만 받는다. 직전 기간까지 한 번에 읽고 날짜로 나눈다.
            // 몫 배분은 정산과 같게 건마다 판매가 × 예약 때 요율을 반올림해서 더한다
            soldRows = jdbc.query("""
                    SELECT to_char(b."requestedAt" AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD') AS day, pa.currency, b."partnerId", b."productId",
                      COUNT(*) AS count, SUM(b.amount) AS amount, SUM(b.net) AS net, SUM(b.commission) AS commission,
                      SUM(ROUND(b.amount * b."affiliateShareRate" / 100.0))::float8 AS affiliate,
                      SUM(ROUND((b.amount * b."pgFeeRate" / 100.0)::numeric))::float8 AS pg
                    FROM "Booking" b JOIN "Partner" pa ON pa.id = b."partnerId"
                    WHERE b."partnerId" IN (:pids) AND b.status IN ('confirmed', 'completed')
                      AND b."requestedAt" >= :prevFrom AND b."requestedAt" < :end
                    GROUP BY 1, 2, 3, 4""", p,
                    (rs, i) -> new SoldRow(rs.getString("day"), rs.getString("currency"), rs.getString("partnerId"), rs.getString("productId"),
                            rs.getLong("count"), rs.getLong("amount"), rs.getLong("net"), rs.getLong("commission"),
                            rs.getDouble("affiliate"), rs.getDouble("pg")));
            groups = jdbc.query("""
                    SELECT b."productId", b.status::text AS status, COUNT(*) AS count, COALESCE(SUM(b.amount), 0) AS amount
                    FROM "Booking" b
                    WHERE b."partnerId" IN (:pids) AND b."requestedAt" >= :from AND b."requestedAt" < :end
                      AND b.status IN ('confirmed', 'completed', 'canceled', 'refunded')
                    GROUP BY 1, 2""", p,
                    (rs, i) -> new Group(rs.getString("productId"), rs.getString("status"), rs.getLong("count"), rs.getLong("amount")));
        }

        // 상품 이름·업체 (보는 사람 언어로, AI 번역이면 nameAi)
        Set<String> productIds = new HashSet<>();
        groups.forEach(g -> productIds.add(g.productId()));
        soldRows.forEach(r -> productIds.add(r.productId()));
        record Info(String name, String partnerId, boolean nameAi) {}
        Map<String, Info> productInfo = new HashMap<>();
        if (!productIds.isEmpty()) {
            Map<String, String> localized = names.names(productIds, locale);
            jdbc.query("SELECT id, name, \"partnerId\" FROM \"Product\" WHERE id IN (:ids)", new MapSqlParameterSource("ids", productIds), rs -> {
                String id = rs.getString("id");
                String name = rs.getString("name");
                String l = localized.getOrDefault(id, name);
                productInfo.put(id, new Info(l, rs.getString("partnerId"), !l.equals(name)));
            });
        }

        // 추이 칸: 62일 이하면 일별(YYYY-MM-DD), 넘으면 월별(YYYY-MM)
        boolean daily = days <= 62;
        List<Bucket> series = new ArrayList<>();
        if (daily) {
            for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) series.add(new Bucket(d.toString(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()));
        } else {
            for (YearMonth m = YearMonth.from(f); !m.isAfter(YearMonth.from(t)); m = m.plusMonths(1)) {
                series.add(new Bucket(m.toString(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()));
            }
        }
        Map<String, Bucket> seriesByKey = new HashMap<>();
        for (Bucket b : series) seriesByKey.put(b.key(), b);

        Map<String, long[]> sales = new LinkedHashMap<>();        // currency → [amount, prevAmount]
        Map<String, double[]> settlement = new LinkedHashMap<>(); // currency → [amount, prevAmount]
        Map<String, Long> partnerSettlement = new HashMap<>();
        Map<String, Map<String, Long>> topCounts = new LinkedHashMap<>();
        long soldTotal = 0;
        long prevSoldTotal = 0;
        for (SoldRow r : soldRows) {
            double share = "partner".equals(caller.role()) ? r.net()
                    : caller.isAffiliate() ? r.affiliate() : r.commission() - r.pg() - r.affiliate();
            if (r.day().compareTo(from) < 0) {
                if (r.day().compareTo(prev.to()) > 0) continue; // 비교 기간과 기간 사이
                sales.computeIfAbsent(r.currency(), k -> new long[2])[1] += r.amount();
                settlement.computeIfAbsent(r.currency(), k -> new double[2])[1] += share;
                prevSoldTotal += r.count();
                continue;
            }
            sales.computeIfAbsent(r.currency(), k -> new long[2])[0] += r.amount();
            settlement.computeIfAbsent(r.currency(), k -> new double[2])[0] += share;
            partnerSettlement.merge(r.partnerId(), r.net(), Long::sum);
            soldTotal += r.count();
            Bucket s = seriesByKey.get(daily ? r.day() : r.day().substring(0, 7));
            if (s == null) continue;
            s.amount().merge(r.currency(), r.amount(), Long::sum);
            s.sold().merge(r.currency(), r.count(), Long::sum);
            topCounts.computeIfAbsent(s.key() + "|" + r.currency(), k -> new LinkedHashMap<>()).merge(r.productId(), r.count(), Long::sum);
        }
        // 판매가 하나도 없어도 판매금액 카드는 0으로 보인다
        if (sales.isEmpty()) {
            String currency = scoped.isEmpty() ? "JPY" : scoped.get(0).getCurrency();
            sales.put(currency, new long[2]);
            settlement.put(currency, new double[2]);
        }
        for (Map.Entry<String, Map<String, Long>> e : topCounts.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            List<Top> top = e.getValue().entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .map(x -> {
                        Info info = productInfo.get(x.getKey());
                        return new Top(info == null ? "-" : info.name(), info != null && info.nameAi(), x.getValue());
                    }).toList();
            seriesByKey.get(k[0]).top().put(k[1], top);
        }

        Map<String, long[]> productStats = new LinkedHashMap<>(); // productId → [sold, canceled, amount]
        Map<String, long[]> partnerSales = new HashMap<>();
        long canceledTotal = 0;
        for (Group g : groups) {
            Info info = productInfo.get(g.productId());
            Partner partner = info == null ? null : partnerById.get(info.partnerId());
            if (partner == null) continue;
            long[] ps = productStats.computeIfAbsent(g.productId(), k -> new long[3]);
            long[] pa = partnerSales.computeIfAbsent(partner.getId(), k -> new long[3]);
            for (long[] row : List.of(ps, pa)) {
                if (SOLD.contains(g.status())) {
                    row[0] += g.count();
                    row[2] += g.amount();
                } else row[1] += g.count(); // 취소 = 환불 (환불완료도 취소로 센다)
            }
            if (!SOLD.contains(g.status())) canceledTotal += g.count();
        }

        List<ProductStat> products = new ArrayList<>();
        for (Map.Entry<String, long[]> e : productStats.entrySet()) {
            Info info = productInfo.get(e.getKey());
            Partner partner = partnerById.get(info.partnerId());
            long[] v = e.getValue();
            products.add(new ProductStat(e.getKey(), info.name(), info.nameAi(), partner.getName(), partner.getCurrency(), v[0], v[1], v[2]));
        }
        products.sort((a, b) -> a.sold() != b.sold() ? Long.compare(b.sold(), a.sold()) : Long.compare(b.amount(), a.amount()));

        List<PartnerStat> partnerStats = scoped.stream().map(p -> {
            long[] v = partnerSales.getOrDefault(p.getId(), new long[3]);
            return new PartnerStat(p.getId(), p.getName(), p.getCurrency(), v[0], v[1], v[2], partnerSettlement.getOrDefault(p.getId(), 0L));
        }).toList();

        List<Money> salesRows = sales.entrySet().stream().map(e -> new Money(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        List<Money> settlementRows = settlement.entrySet().stream()
                .map(e -> new Money(e.getKey(), Math.round(e.getValue()[0]), Math.round(e.getValue()[1]))).toList();
        return new StatsData(prev, daily ? "day" : "month", series, soldTotal, prevSoldTotal, canceledTotal,
                salesRows, new Settlement(settlementRows), products, partnerStats);
    }
}
