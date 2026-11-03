package com.crosshub.backoffice.product.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Solar 가 뽑은 값을 결정적인 코드로 검산한다. "Solar 가 정규화하고, 코드가 검산한다".
 *
 * LLM 은 문맥 해석(어느 숫자가 아동가인지, 어디가 판매기간인지)에 강하고 자릿수·연호 계산에 약하다.
 * 그래서 값은 Solar 가 내되, 그 값의 근거(원문 인용)를 함께 받아 두 가지를 확인한다.
 *  1) grounding — 인용이 실제 문서에 있는가 (없으면 지어낸 값)
 *  2) 재계산 — 인용을 이 클래스의 파서로 다시 읽어 Solar 의 값과 같은가 (48000 → 4800 같은 자릿수 실수)
 * 파서가 읽지 못하는 표기는 Solar 값을 믿는다 (범위 검사만). 막는 게 아니라 확인하는 쪽이라 처음 보는 사이트에서도 멈추지 않는다.
 *
 * 문제는 사람이 읽을 문장으로 돌려준다 — 그대로 Solar 재요청 메시지가 된다.
 */
final class ExtractCheck {

    private ExtractCheck() {}

    /** 전각→반각(NFKC), 공백 제거. 문서와 인용을 같은 기준으로 비교하려고 쓴다 */
    static String canon(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("[\\s|*`#>]", "");
    }

    private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(万)?(?:\\s*(\\d[\\d,]*))?");

    /** 금액 표기 → 엔 단위 정수. ¥48,000 · 48,000円 · 4.8万円 · 1万2,000円. 못 읽으면 null */
    static Long yen(String text) {
        if (text == null) return null;
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        Matcher m = AMOUNT.matcher(s);
        if (!m.find()) return null;
        try {
            double base = Double.parseDouble(m.group(1).replace(",", ""));
            if (m.group(2) == null) return Math.round(base);
            long rest = m.group(3) == null ? 0 : Long.parseLong(m.group(3).replace(",", ""));
            return Math.round(base * 10_000) + rest;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 2026/4/1 · 2026.4.1 · 2026-04-01 · 2026年4月1日 · 令和8年4月1日 · R8.4.1
    private static final Pattern DATE = Pattern.compile("(令和|平成|R|H)?\\s*(\\d{1,4})\\s*[年/.\\-]\\s*(\\d{1,2})\\s*[月/.\\-]\\s*(\\d{1,2})");

    /** 글 안의 날짜를 나온 순서대로. 연호는 서기로 바꾼다 (令和1년=2019, 平成1년=1989) */
    static List<LocalDate> dates(String text) {
        List<LocalDate> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = DATE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (m.find()) {
            int y = Integer.parseInt(m.group(2));
            String era = m.group(1);
            if ("令和".equals(era) || "R".equals(era)) y += 2018;
            else if ("平成".equals(era) || "H".equals(era)) y += 1988;
            else if (y < 1000) continue; // 연도 없는 "4/1" 은 여기서 다루지 않는다
            try {
                out.add(LocalDate.of(y, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
            } catch (DateTimeException ignored) {
                // 13월 같은 건 날짜가 아니다
            }
        }
        return out;
    }

    static LocalDate iso(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** 인용이 문서에 실제로 있는가. 짧은 조각은 우연히 겹치므로 4글자 미만은 통과시킨다 */
    static boolean grounded(String quote, String docCanon) {
        String q = canon(quote);
        return q.length() < 4 || docCanon.contains(q);
    }

    private static final Pattern PIECE_SEP = Pattern.compile("[\\s|/／・、：:()（）\\[\\]「」【】]+");

    /**
     * 조각 단위 근거: 인용을 공백·칸 구분 기호로 나눈 조각(2글자 이상)이 모두 출처 블록 안에 있으면 근거로 본다.
     * 표가 아닌 칸 배치(div 격자·이미지 OCR)에서 모델은 "3日間 大人 22,800円" 처럼 머리글과 칸을 이어 붙여 인용한다.
     * 글자 그대로는 문서에 없지만 조각은 모두 그 블록에 있다 — 지어낸 숫자·이름은 여전히 걸린다
     */
    static boolean groundedPieces(String quote, String blockCanon) {
        if (quote == null || blockCanon.isEmpty()) return false;
        int pieces = 0;
        for (String raw : PIECE_SEP.split(Normalizer.normalize(quote, Normalizer.Form.NFKC))) {
            String p = canon(raw);
            if (p.length() < 2) continue;
            if (!blockCanon.contains(p)) return false;
            pieces++;
        }
        return pieces >= 2;
    }

    /** 근거가 출처 블록에 글자 그대로, 또는 조각 단위로 있다 (금액·플랜 검사는 따로 한다) */
    private static boolean inCitedBlock(JsonNode item, Ctx ctx) {
        Blocks.Block b = ctx.blocks().get(item.path("block").asText("").strip());
        return b != null && groundedPieces(item.path("evidence").asText(""), canon(b.text()));
    }

    /**
     * 검산에 쓰는 문서 정보. 블록이 있으면(정상 경로) 항목마다 출처 블록을 대조하고 표는 블록 안의 표로 결합을 본다.
     * 블록 없이 글만 주면(단위 테스트·예전 경로) 문서 전체를 기준으로 본다
     */
    record Ctx(String doc, String docCanon, Map<String, Blocks.Block> blocks, Map<String, String> labels) {
        static Ctx of(String doc) {
            return new Ctx(doc, canon(doc), Map.of(), Map.of());
        }

        static Ctx of(List<Blocks.Block> blocks, Map<String, String> labels) {
            String doc = Blocks.render(blocks, labels);
            return new Ctx(doc, canon(doc), Blocks.byId(blocks), labels);
        }

        boolean hasBlocks() {
            return !blocks.isEmpty();
        }

        String label(String id) {
            return labels.getOrDefault(id, "");
        }

        /** 결합 검사에 쓸 표: 출처 블록 안의 표, 블록을 모르면 문서 전체의 표 */
        List<TableGrid.Table> tables(String blockId) {
            Blocks.Block b = blocks.get(blockId);
            return TableGrid.findAll(b != null ? b.text() : doc);
        }
    }

    /** 검산 결과. 문제가 없으면 빈 목록 */
    static List<String> problems(JsonNode draft, String doc) {
        return problems(draft, Ctx.of(doc));
    }

    static List<String> problems(JsonNode draft, Ctx ctx) {
        String docCanon = ctx.docCanon();
        List<String> out = new ArrayList<>();
        JsonNode rows = draft.path("priceRows");
        JsonNode adjustments = draft.path("adjustments");
        for (int i = 0; i < rows.size(); i++) out.addAll(row("priceRows[" + i + "]", rows.get(i), ctx, adjustments));
        out.addAll(monotonic(rows));
        out.addAll(pairs(rows, ctx));
        for (int i = 0; i < adjustments.size(); i++) out.addAll(adjustment("adjustments[" + i + "]", adjustments.get(i), ctx));
        JsonNode days = draft.path("itinerary");
        for (int i = 0; i < days.size(); i++) out.addAll(day("itinerary[" + i + "]", days.get(i), ctx));
        out.addAll(dayOrder(days, docCanon));
        out.addAll(sale(draft.path("salePeriod"), ctx));
        JsonNode rules = draft.path("cancelRules");
        for (int i = 0; i < rules.size(); i++) out.addAll(rule("cancelRules[" + i + "]", rules.get(i), ctx));
        String locale = draft.path("sourceLocale").asText("");
        out.addAll(description(draft.path("description"), docCanon, locale));
        JsonNode sections = draft.path("sections");
        for (int i = 0; i < sections.size(); i++) out.addAll(section("sections[" + i + "]", sections.get(i), ctx, locale));
        for (Blocks.Block b : uncovered(draft, ctx)) {
            out.add("블록 " + b.id() + "(" + ctx.label(b.id()) + ")의 내용이 어느 항목에도 쓰이지 않았습니다: '" + Blocks.snippet(b) + "'. "
                    + "상품 정보라면 알맞은 칸(" + FIELD_FOR.getOrDefault(ctx.label(b.id()), "sections") + ")에 넣고 출처 블록으로 " + b.id() + " 를 적으세요.");
        }
        return out;
    }

    /** 재요청 뒤에도 남은 문제는 그 항목만 버린다 — 틀린 값보다 빈 값이 안전하다. 버린 개수를 돌려준다 */
    static int dropInvalid(ObjectNode draft, String doc) {
        return dropInvalid(draft, Ctx.of(doc));
    }

    static int dropInvalid(ObjectNode draft, Ctx ctx) {
        String docCanon = ctx.docCanon();
        int dropped = 0;
        JsonNode adjustments = draft.path("adjustments");
        for (String field : List.of("priceRows", "cancelRules", "adjustments", "itinerary")) {
            JsonNode arr = draft.path(field);
            if (!arr.isArray()) continue;
            ArrayNode kept = draft.arrayNode();
            for (JsonNode item : arr) {
                boolean ok = switch (field) {
                    case "priceRows" -> row("", item, ctx, adjustments).isEmpty();
                    case "adjustments" -> adjustment("", item, ctx).isEmpty();
                    case "itinerary" -> day("", item, ctx).isEmpty();
                    default -> rule("", item, ctx).isEmpty();
                };
                if (ok) kept.add(item);
                else dropped++;
            }
            draft.set(field, kept);
        }
        String locale = draft.path("sourceLocale").asText("");
        if (!description(draft.path("description"), docCanon, locale).isEmpty()) {
            ObjectNode empty = draft.objectNode();
            empty.putArray("highlights");
            empty.put("summary", "");
            draft.set("description", empty);
            dropped++;
        }
        JsonNode sections = draft.path("sections");
        if (sections.isArray()) {
            ArrayNode kept = draft.arrayNode();
            for (JsonNode sec : sections) {
                if (section("", sec, ctx, locale).isEmpty()) kept.add(sec);
                else dropped++;
            }
            draft.set("sections", kept);
        }
        if (!sale(draft.path("salePeriod"), ctx).isEmpty()) {
            ObjectNode empty = draft.objectNode();
            empty.put("block", "").put("evidence", "").put("start", "").put("end", "");
            draft.set("salePeriod", empty);
            dropped++;
        }
        return dropped;
    }

    /** 출처 블록 대조: 적은 블록이 있고, 근거 인용이 그 블록 안에 있어야 한다. 블록을 모르는 경로에서는 건너뛴다 */
    private static List<String> source(String at, JsonNode item, Ctx ctx) {
        if (!ctx.hasBlocks()) return List.of();
        String id = item.path("block").asText("").strip();
        Blocks.Block b = ctx.blocks().get(id);
        if (b == null) return List.of(at + ".block '" + id + "' 는 없는 블록입니다. 근거가 있는 블록 이름(B3 등)을 적으세요.");
        String evidence = item.path("evidence").asText("");
        if (!grounded(evidence, canon(b.text()))) return List.of(at + ".evidence 가 블록 " + id + " 안에 없습니다. 그 블록의 원문을 복사하거나 맞는 블록을 적으세요.");
        return List.of();
    }

    /** 일수·박수가 앞에 붙은 플랜 이름 (3日間, 2泊3日, 3 days, 2박 3일). 길수록 요금이 같거나 커야 한다 */
    private static final Pattern PLAN_LENGTH = Pattern.compile("(\\d+)\\s*(?:日間|日|泊|days?|nights?|일|박)");

    private static List<String> row(String at, JsonNode r, Ctx ctx, JsonNode adjustments) {
        List<String> out = new ArrayList<>();
        String evidence = r.path("evidence").asText("");
        String amountText = r.path("amountText").asText("");
        double amount = r.path("amount").asDouble(0);
        String plan = r.path("plan").asText("");
        String block = r.path("block").asText("").strip();
        boolean tableRow = evidence.contains("|") || evidence.contains("<td");
        TableGrid.Binding bound = amount > 0
                ? TableGrid.bind(ctx.tables(block), plan, r.path("category").asText(""), Math.round(amount))
                : new TableGrid.Binding(TableGrid.Fit.NOT_IN_TABLE, "", "");
        // 표의 한 줄은 모델이 옮겨 적으며 칸 구분·공백·병합 칸 반복이 조금씩 달라진다. 금액이 그 블록 표의 (플랜, 인원) 칸에
        // 실제로 있으면(결합 MATCH) 글자 그대로의 인용보다 강한 근거라서, 인용 불일치는 문제로 보지 않는다
        boolean boundInBlock = tableRow && bound.fit() == TableGrid.Fit.MATCH && ctx.blocks().containsKey(block);
        if (!boundInBlock && !inCitedBlock(r, ctx)) {
            if (!grounded(evidence, ctx.docCanon())) out.add(at + ".evidence 가 문서에 없습니다. 문서의 원문을 그대로 복사하세요.");
            out.addAll(source(at, r, ctx));
        }
        // 결합 검사: 숫자가 문서에 있는 것만으로는 부족하다. 그 숫자가 이 행·이 열의 요금이어야 한다
        if ("adjustment".equals(ctx.label(block))) {
            out.add(at + " 의 근거 블록 " + block + " 은 할인·할증 블록입니다. 그 금액은 adjustments 에 넣고, 기본 요금을 읽을 수 없으면 그 칸은 비우세요.");
        }
        if (amount > 0) {
            if (bound.fit() == TableGrid.Fit.MISPLACED) {
                out.add(at + ": 표에서 " + Math.round(amount) + " 은 행 '" + bound.rowLabel() + "' / 열 '" + bound.columnLabel()
                        + "' 칸에 있습니다. plan '" + plan + "' / category '" + r.path("category").asText("") + "' 와 맞지 않습니다. 표의 칸대로 고치거나 그 칸을 비우세요.");
            } else if (bound.fit() == TableGrid.Fit.NOT_IN_TABLE && tableRow) {
                out.add(at + ": " + Math.round(amount) + " 이 표의 어느 칸에도 없습니다. 표에 있는 금액만 넣으세요.");
            }
        }
        if (!plan.isBlank() && !tableRow && !canon(evidence).contains(canon(plan))) {
            out.add(at + ".evidence 에 플랜 '" + plan + "' 이 없습니다. 이 금액이 그 플랜의 요금이라는 근거를 복사하거나, 아니면 그 칸을 비우세요.");
        }
        for (JsonNode a : adjustments) {
            if (Math.round(a.path("amount").asDouble(-1)) == Math.round(amount) && amount > 0) {
                out.add(at + ".amount " + Math.round(amount) + " 은 adjustments 의 '" + a.path("label").asText("") + "' 금액과 같습니다. 기본 요금이 아니면 빼세요.");
                break;
            }
        }
        if (!canon(evidence).contains(canon(amountText))) out.add(at + ".amountText(" + amountText + ")가 evidence 안에 없습니다.");
        if (!"JPY".equals(r.path("currency").asText())) out.add(at + " 의 통화가 엔화가 아닙니다. 엔화 요금만 넣으세요.");
        if (amount <= 0) out.add(at + ".amount 는 0보다 커야 합니다.");
        Long recomputed = yen(amountText);
        if (recomputed != null && recomputed != Math.round(amount)) {
            out.add(at + ".amount=" + Math.round(amount) + " 이지만 amountText '" + amountText + "' 는 " + recomputed + " 입니다.");
        }
        return out;
    }

    /** 같은 인원 구분에서 일정이 길어지는데 요금이 내려가면 다른 숫자가 섞였을 가능성이 크다. 재요청 사유로만 쓰고 버리지는 않는다 */
    static List<String> monotonic(JsonNode rows) {
        List<String> out = new ArrayList<>();
        java.util.Map<String, java.util.TreeMap<Integer, long[]>> byCategory = new java.util.HashMap<>();
        for (JsonNode r : rows) {
            Matcher m = PLAN_LENGTH.matcher(Normalizer.normalize(r.path("plan").asText(""), Normalizer.Form.NFKC));
            if (!m.find()) continue;
            byCategory.computeIfAbsent(canon(r.path("category").asText("")), k -> new java.util.TreeMap<>())
                    .putIfAbsent(Integer.parseInt(m.group(1)), new long[] {Math.round(r.path("amount").asDouble())});
        }
        for (var e : byCategory.entrySet()) {
            long prev = -1;
            int prevLen = 0;
            for (var len : e.getValue().entrySet()) {
                long amount = len.getValue()[0];
                if (prev >= 0 && amount < prev) {
                    out.add("priceRows: '" + (e.getKey().isEmpty() ? "-" : e.getKey()) + "' 요금이 " + prevLen + "일/박 " + prev + " 보다 "
                            + len.getKey() + "일/박 " + amount + " 이 더 쌉니다. 다른 숫자(할인액 등)가 섞이지 않았는지 확인하고, 읽을 수 없는 칸은 비우세요.");
                    break;
                }
                prev = amount;
                prevLen = len.getKey();
            }
        }
        return out;
    }

    /** "17,800円（12,500円）" — 금액 뒤 괄호 속 두 번째 금액 */
    private static final Pattern PAIRED = Pattern.compile("(\\d[\\d,]*)\\s*円?\\s*[（(]\\s*(\\d[\\d,]*)\\s*円?\\s*[）)]");

    /**
     * 한 칸에 금액이 둘(괄호 속 두 번째 금액)인데 하나만 가져간 경우.
     * 머리글이 "大人／（　）内は小人" 처럼 두 인원 구분을 함께 말하면 결합 검사(열 이름 포함)는 통과해 버린다.
     * 그래서 이름이 아니라 칸 구조로 본다: 첫 금액을 쓴 행이 있으면 같은 플랜에 두 번째 금액을 다른 인원 구분으로 쓴 행도 있어야 한다.
     * 재요청 사유로만 쓰고 버리지는 않는다
     */
    static List<String> pairs(JsonNode rows, Ctx ctx) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode r = rows.get(i);
            long amount = Math.round(r.path("amount").asDouble());
            Long second = companion(amount, r.path("evidence").asText(""), ctx.tables(r.path("block").asText("").strip()));
            if (second == null) continue;
            String plan = canon(r.path("plan").asText(""));
            String category = canon(r.path("category").asText(""));
            boolean found = false;
            boolean sameCategory = false;
            for (JsonNode o : rows) {
                if (o == r || Math.round(o.path("amount").asDouble()) != second || !canon(o.path("plan").asText("")).equals(plan)) continue;
                if (canon(o.path("category").asText("")).equals(category)) sameCategory = true;
                else found = true;
            }
            if (found) continue;
            out.add("priceRows[" + i + "]: 같은 칸에 괄호 속 금액 " + second + " 이 함께 있습니다 (" + amount + "（" + second + "）). "
                    + (sameCategory ? "두 금액을 같은 인원 구분 '" + r.path("category").asText("") + "' 에 넣었습니다. " : "")
                    + "괄호 속 금액은 보통 다른 인원 구분(표 머리글이 말하는 쪽, 예: 「（　）内は小人」)의 요금입니다. "
                    + "머리글을 보고 첫 금액과 괄호 속 금액의 인원 구분을 각각 정해 두 행으로 넣으세요.");
        }
        return out;
    }

    /** amount 가 첫 금액이고 같은 칸(또는 근거의 "a（b）")에 두 번째 금액 b 가 있으면 b */
    private static Long companion(long amount, String evidence, List<TableGrid.Table> tables) {
        for (TableGrid.Table t : tables) {
            for (List<String> row : t.rows()) {
                for (String cell : row) {
                    List<Long> found = TableGrid.amounts(cell);
                    if (found.size() >= 2 && found.get(0) == amount && found.get(1) != amount) return found.get(1);
                }
            }
        }
        Matcher m = PAIRED.matcher(Normalizer.normalize(evidence, Normalizer.Form.NFKC));
        while (m.find()) {
            Long a = yen(m.group(1));
            Long b = yen(m.group(2));
            if (a != null && b != null && a == amount && b >= 100 && b != amount) return b;
        }
        return null;
    }

    private static List<String> adjustment(String at, JsonNode a, Ctx ctx) {
        List<String> out = new ArrayList<>();
        String evidence = a.path("evidence").asText("");
        String amountText = a.path("amountText").asText("");
        double amount = a.path("amount").asDouble(0);
        if (!inCitedBlock(a, ctx)) {
            if (!grounded(evidence, ctx.docCanon())) out.add(at + ".evidence 가 문서에 없습니다.");
            out.addAll(source(at, a, ctx));
        }
        if (!canon(evidence).contains(canon(amountText))) out.add(at + ".amountText(" + amountText + ")가 evidence 안에 없습니다.");
        if (amount <= 0) out.add(at + ".amount 는 0보다 커야 합니다.");
        Long recomputed = yen(amountText);
        if (recomputed != null && recomputed != Math.round(amount)) {
            out.add(at + ".amount=" + Math.round(amount) + " 이지만 amountText '" + amountText + "' 는 " + recomputed + " 입니다.");
        }
        return out;
    }

    /** 일정 한 줄: 근거가 문서에 있고, text 의 숫자(시각·일차)는 모두 근거 안에 있어야 한다 — 다른 날의 시각을 끌어오는 것을 막는다 */
    private static List<String> day(String at, JsonNode d, Ctx ctx) {
        List<String> out = new ArrayList<>();
        String evidence = d.path("evidence").asText("");
        if (!grounded(evidence, ctx.docCanon())) out.add(at + ".evidence 가 문서에 없습니다. 그 날의 일정 원문을 그대로 복사하세요.");
        out.addAll(source(at, d, ctx));
        String allowed = canon(evidence + d.path("day").asText("")).replace(",", "");
        Matcher m = NUMBER.matcher(Normalizer.normalize(d.path("text").asText(""), Normalizer.Form.NFKC));
        while (m.find()) {
            String n = m.group().replaceAll("[,.]+$", "").replace(",", "");
            if (!n.isEmpty() && !allowed.contains(n)) {
                out.add(at + ".text 의 '" + m.group() + "' 가 그 날의 원문(evidence)에 없습니다. 그 날에 적힌 내용만 옮기세요.");
                break;
            }
        }
        if (LEADER_DOTS.matcher(d.path("text").asText("")).find()) out.add(at + ".text 에 점선(······)이 남아 있습니다. → 로 바꾸세요.");
        return out;
    }

    /** 일정 순서: 각 날의 근거가 문서에 나오는 위치가 앞에서 뒤로 가야 한다 */
    static List<String> dayOrder(JsonNode days, String docCanon) {
        int last = -1;
        for (int i = 0; i < days.size(); i++) {
            String q = canon(days.get(i).path("evidence").asText(""));
            if (q.length() < 4) continue;
            int at = docCanon.indexOf(q);
            if (at < 0) continue;
            if (at < last) return List.of("itinerary[" + i + "] 가 문서의 순서와 다릅니다. 문서에 나온 순서대로 넣으세요.");
            last = at;
        }
        return List.of();
    }

    // ── 누락 검사(재현율) ── 블록 라벨이 상품 내용(요금·일정·숙박 …)인데 어느 항목도 그 블록을 출처로 적지 않았으면 "빠졌다".
    // 주제 키워드를 정해 두지 않고 블록 라벨과 출처 표시만 보므로 사이트·언어와 무관하다

    /** 출처로 쓰이지 않아도 되는 블록: 상품명·소개는 이름·설명(문장 필드)으로 녹아든다, 무관은 버린 것 */
    static final java.util.Set<String> NOT_REQUIRED = java.util.Set.of("title", "intro", "irrelevant", "");
    /** 재요청 문장에 붙이는 "어디에 넣을지" 안내 */
    static final Map<String, String> FIELD_FOR = Map.ofEntries(
            Map.entry("price", "priceRows"), Map.entry("adjustment", "adjustments"), Map.entry("sale_period", "salePeriod"),
            Map.entry("itinerary", "itinerary"), Map.entry("program", "sections kind program"),
            Map.entry("transport", "sections kind transport"), Map.entry("lodging", "sections kind lodging"),
            Map.entry("included", "sections kind other"), Map.entry("meals", "sections kind other"),
            Map.entry("guide", "sections kind other"), Map.entry("policy", "cancelRules 또는 sections kind other"),
            Map.entry("notice", "sections kind other"));

    /** 내용 블록인데 어느 항목도 출처로 적지 않은 블록 (문서 순서) */
    static List<Blocks.Block> uncovered(JsonNode draft, Ctx ctx) {
        List<Blocks.Block> out = new ArrayList<>();
        if (!ctx.hasBlocks()) return out;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String field : List.of("priceRows", "adjustments", "itinerary", "cancelRules")) {
            for (JsonNode item : draft.path(field)) used.add(item.path("block").asText("").strip());
        }
        used.add(draft.path("salePeriod").path("block").asText("").strip());
        for (JsonNode s : draft.path("sections")) for (JsonNode id : s.path("blocks")) used.add(id.asText("").strip());
        for (Blocks.Block b : ctx.blocks().values()) {
            if (!NOT_REQUIRED.contains(ctx.label(b.id())) && !used.contains(b.id())) out.add(b);
        }
        return out;
    }

    private static List<String> sale(JsonNode sale, Ctx ctx) {
        String docCanon = ctx.docCanon();
        List<String> out = new ArrayList<>();
        String startText = sale.path("start").asText("");
        String endText = sale.path("end").asText("");
        LocalDate start = iso(startText);
        LocalDate end = iso(endText);
        String evidence = sale.path("evidence").asText("");
        if (!startText.isBlank() && start == null) out.add("salePeriod.start 는 YYYY-MM-DD 형식이어야 합니다.");
        if (!endText.isBlank() && end == null) out.add("salePeriod.end 는 YYYY-MM-DD 형식이어야 합니다.");
        if ((start != null || end != null) && !grounded(evidence, docCanon)) out.add("salePeriod.evidence 가 문서에 없습니다.");
        if (start != null || end != null) out.addAll(source("salePeriod", sale, ctx));
        if (start != null && end != null && end.isBefore(start)) out.add("salePeriod.end 가 start 보다 앞섭니다.");
        List<LocalDate> found = dates(evidence);
        if (found.size() >= 2) {
            LocalDate first = found.get(0), last = found.get(found.size() - 1);
            if (start != null && !start.equals(first)) out.add("salePeriod.start=" + start + " 이지만 evidence 의 첫 날짜는 " + first + " 입니다.");
            if (end != null && !end.equals(last)) out.add("salePeriod.end=" + end + " 이지만 evidence 의 마지막 날짜는 " + last + " 입니다.");
        }
        return out;
    }

    private static List<String> rule(String at, JsonNode r, Ctx ctx) {
        String docCanon = ctx.docCanon();
        List<String> out = new ArrayList<>();
        out.addAll(source(at, r, ctx));
        double pct = r.path("percent").asDouble(-1);
        if (pct < 0 || pct > 100) out.add(at + ".percent 는 0~100 이어야 합니다.");
        if (r.path("daysBefore").asDouble(-1) < 0) out.add(at + ".daysBefore 는 0 이상이어야 합니다.");
        if (!grounded(r.path("evidence").asText(""), docCanon)) out.add(at + ".evidence 가 문서에 없습니다.");
        return out;
    }
    // ── 문장 필드 lint ── 사실 필드는 원문과 같아야 하고(grounding), 문장 필드는 새로 쓴 글이라 형식·문체·숫자만 본다

    static final int HIGHLIGHT_MAX = 40;
    static final int SUMMARY_MAX = 200;
    static final int SUMMARY_SENTENCES_MAX = 3;
    static final int SECTION_MAX = 4000;
    /** 한국어 글에서 한글 글자 수 대비 공백 비율 하한. 보통 문장은 0.2~0.3, 띄어쓰기가 없으면 0 에 가깝다 */
    static final double KO_SPACE_MIN = 0.1;

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,.]*");
    private static final Pattern LEADER_DOTS = Pattern.compile("[·・]{3,}|\\.{4,}|…{2,}");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。．.!！?？])\\s*");

    private static List<String> description(JsonNode d, String docCanon, String locale) {
        List<String> out = new ArrayList<>();
        JsonNode highlights = d.path("highlights");
        String summary = d.path("summary").asText("").strip();
        List<String> all = new ArrayList<>();
        if (highlights.size() > 0 && (highlights.size() < 3 || highlights.size() > 5)) {
            out.add("description.highlights 는 3~5개여야 합니다 (지금 " + highlights.size() + "개).");
        }
        for (int i = 0; i < highlights.size(); i++) {
            String h = highlights.get(i).asText("").strip();
            if (h.isEmpty()) out.add("description.highlights[" + i + "] 가 비었습니다.");
            if (h.codePointCount(0, h.length()) > HIGHLIGHT_MAX) out.add("description.highlights[" + i + "] 는 " + HIGHLIGHT_MAX + "자 이내로 줄이세요.");
            all.add(h);
        }
        if (summary.codePointCount(0, summary.length()) > SUMMARY_MAX) out.add("description.summary 는 " + SUMMARY_MAX + "자 이내로 줄이세요.");
        List<String> sentences = sentences(summary);
        if (sentences.size() > SUMMARY_SENTENCES_MAX) out.add("description.summary 는 " + SUMMARY_SENTENCES_MAX + "문장 이내로 쓰세요.");
        if (!highlights.isEmpty() && summary.isEmpty()) out.add("description.summary 가 비었습니다.");
        all.addAll(sentences);
        out.addAll(prose("description", String.join("\n", all), all, docCanon, locale));
        return out;
    }

    private static List<String> section(String at, JsonNode s, Ctx ctx, String locale) {
        String docCanon = ctx.docCanon();
        List<String> out = new ArrayList<>();
        if (ctx.hasBlocks()) {
            for (JsonNode id : s.path("blocks")) {
                if (!ctx.blocks().containsKey(id.asText(""))) out.add(at + ".blocks 의 '" + id.asText("") + "' 는 없는 블록입니다.");
            }
        }
        String text = s.path("text").asText("").strip();
        if (text.length() > SECTION_MAX) out.add(at + ".text 가 너무 깁니다 (" + SECTION_MAX + "자 이내).");
        if ("other".equals(s.path("kind").asText("")) && !text.isEmpty() && s.path("title").asText("").isBlank()) {
            out.add(at + ".title 이 비었습니다. other 섹션은 내용을 나타내는 짧은 제목이 필요합니다.");
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) if (!line.isBlank()) lines.add(line.strip());
        out.addAll(prose(at + ".text", text, lines, docCanon, locale));
        return out;
    }

    /** 새로 쓴 글 공통 검사: 같은 문장 반복, 문서에 없는 숫자, 한국어 띄어쓰기, 언어 일치 */
    static List<String> prose(String at, String text, List<String> units, String docCanon, String locale) {
        List<String> out = new ArrayList<>();
        if (text.isBlank()) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String u : units) {
            String k = canon(u);
            if (k.length() >= 4 && !seen.add(k)) {
                out.add(at + " 에 같은 문장이 반복됩니다: '" + u + "'");
                break;
            }
        }
        String docDigits = docCanon.replace(",", "");
        Matcher m = NUMBER.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (m.find()) {
            String n = m.group().replaceAll("[,.]+$", "").replace(",", "");
            if (!n.isEmpty() && !docDigits.contains(n)) {
                out.add(at + " 의 숫자 '" + m.group() + "' 가 문서에 없습니다. 문서에 있는 숫자만 쓰세요.");
                break;
            }
        }
        if (LEADER_DOTS.matcher(text).find()) out.add(at + " 에 표의 점선(······)이 남아 있습니다. 말이나 → 로 바꿔 쓰세요.");
        if ("ko".equals(locale)) {
            long hangul = text.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL).count();
            long spaces = text.chars().filter(c -> c == ' ').count();
            if (hangul >= 20 && (double) spaces / hangul < KO_SPACE_MIN) out.add(at + " 의 띄어쓰기가 거의 없습니다. 한국어 맞춤법대로 띄어 쓰세요.");
        }
        String script = script(text);
        if (!script.isEmpty() && !matches(locale, script)) out.add(at + " 가 문서 언어(" + locale + ")로 쓰이지 않았습니다. 번역하지 말고 원문 언어로 쓰세요.");
        return out;
    }

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : SENTENCE.split(text.strip())) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    /** 글의 주 문자: hangul · kana · han · latin. 글자가 적으면 "" (판단 안 함) */
    static String script(String text) {
        long hangul = 0, kana = 0, han = 0, latin = 0;
        for (int c : text.codePoints().toArray()) {
            switch (Character.UnicodeScript.of(c)) {
                case HANGUL -> hangul++;
                case HIRAGANA, KATAKANA -> kana++;
                case HAN -> han++;
                case LATIN -> latin++;
                default -> { }
            }
        }
        long total = hangul + kana + han + latin;
        if (total < 10) return "";
        if (hangul * 2 >= total) return "hangul";
        if (kana > 0 && (kana + han) * 2 >= total) return "kana";
        if (han * 2 >= total) return "han";
        if (latin * 2 >= total) return "latin";
        return "";
    }

    private static boolean matches(String locale, String script) {
        return switch (locale) {
            case "ko" -> script.equals("hangul");
            case "ja" -> script.equals("kana") || script.equals("han");
            case "zh-CN", "zh-TW" -> script.equals("han");
            case "en", "vi" -> script.equals("latin");
            default -> true;
        };
    }
}
