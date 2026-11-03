package com.crosshub.backoffice.product.extract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * 표를 "행 × 열 격자"로 다룬다. 요금이 어느 행(플랜)·어느 열(인원 구분)에 있는지 코드가 확인하는 데 쓴다 (결합 검사).
 *
 * 숫자가 문서 어딘가에 있다는 것만으로는 그 숫자가 "3日間 × 大人" 칸의 요금이라는 증거가 안 된다.
 * 표를 격자로 펴 두면 금액이 실제로 놓인 칸의 행 이름·열 이름을 읽어 Solar 가 말한 (plan, category) 와 맞는지 볼 수 있다.
 * 사이트·언어와 무관하게 표 구조만 본다.
 *
 *  - HTML 표(Document Parse 의 표 요소, 상품 페이지의 table)는 rowspan·colspan 을 펼쳐 병합 칸 값을 걸친 칸마다 채운다
 *  - markdown 파이프 표(| a | b |)는 그대로 격자로 읽는다
 */
final class TableGrid {

    private TableGrid() {}

    /** 격자 하나. rows[0] 이 머리글로 쓰이는 경우가 많지만 확정하지 않는다 — 칸에 금액이 없는 행을 머리글로 본다 */
    record Table(List<List<String>> rows) {}

    enum Fit { MATCH, MISPLACED, NOT_IN_TABLE }

    /** 결합 결과. MISPLACED 면 그 금액이 실제로 있는 칸의 행·열 이름을 담는다 (재요청 문장에 쓴다) */
    record Binding(Fit fit, String rowLabel, String columnLabel) {}

    // ── 만들기 ──

    /** HTML 표 → 병합 칸을 펼친 격자. 표가 아니면 빈 목록.
     *  칸을 놓을 때마다 차지하는 자리(행+rowspan × 열+colspan)를 표시해 두고, 다음 칸은 비어 있는 첫 열에 놓는다 */
    static List<List<String>> fromHtml(String html) {
        List<List<String>> grid = new ArrayList<>();
        if (html == null || html.isBlank()) return grid;
        Element table = Jsoup.parseBodyFragment(html).selectFirst("table");
        if (table == null) return grid;
        java.util.Map<Integer, java.util.TreeMap<Integer, String>> cells = new java.util.HashMap<>();
        int r = 0;
        for (Element tr : table.select("tr")) {
            java.util.TreeMap<Integer, String> row = cells.computeIfAbsent(r, k -> new java.util.TreeMap<>());
            int c = 0;
            for (Element cell : tr.children()) {
                if (!cell.tagName().equals("td") && !cell.tagName().equals("th")) continue;
                while (row.containsKey(c)) c++;
                String text = cell.text().strip();
                int colspan = span(cell.attr("colspan"));
                int rowspan = span(cell.attr("rowspan"));
                for (int dr = 0; dr < rowspan; dr++) {
                    java.util.TreeMap<Integer, String> target = cells.computeIfAbsent(r + dr, k -> new java.util.TreeMap<>());
                    for (int dc = 0; dc < colspan; dc++) target.put(c + dc, text);
                }
                c += colspan;
            }
            r++;
        }
        for (int i = 0; i < r; i++) {
            java.util.TreeMap<Integer, String> row = cells.get(i);
            if (row == null || row.isEmpty()) continue;
            List<String> out = new ArrayList<>();
            for (int c = 0; c <= row.lastKey(); c++) out.add(row.getOrDefault(c, ""));
            grid.add(out);
        }
        return grid;
    }

    private static int span(String v) {
        try {
            int n = Integer.parseInt(v.strip());
            return n < 1 ? 1 : Math.min(n, 50);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 격자 → markdown 파이프 표. 칸 안의 | 와 줄바꿈은 보이게 바꾼다 */
    static String toMarkdown(List<List<String>> grid) {
        if (grid.isEmpty()) return "";
        int width = grid.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < grid.size(); i++) {
            sb.append('|');
            for (int c = 0; c < width; c++) {
                String cell = c < grid.get(i).size() ? grid.get(i).get(c) : "";
                sb.append(' ').append(cell.replace("|", "/").replaceAll("\\s+", " ").strip()).append(" |");
            }
            sb.append('\n');
            if (i == 0) sb.append("|").append(" --- |".repeat(width)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static final Pattern SEPARATOR = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");

    /** 글 안의 markdown 파이프 표를 모두 격자로 */
    static List<Table> findAll(String text) {
        List<Table> out = new ArrayList<>();
        if (text == null) return out;
        List<List<String>> rows = new ArrayList<>();
        for (String raw : (text + "\n").split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("|")) {
                if (SEPARATOR.matcher(line).matches()) continue;
                List<String> cells = new ArrayList<>();
                String body = line.substring(1, line.endsWith("|") && line.length() > 1 ? line.length() - 1 : line.length());
                for (String cell : body.split("\\|", -1)) cells.add(cell.strip());
                rows.add(cells);
            } else if (!rows.isEmpty()) {
                out.add(new Table(rows));
                rows = new ArrayList<>();
            }
        }
        return out;
    }

    // ── 결합 ──

    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?\\s*万?(?:\\s*\\d[\\d,]*)?");

    /** 칸 안의 금액들 (괄호 속 소인 요금처럼 한 칸에 여럿일 수 있다). 3자리 미만 숫자(나이·일수)는 금액으로 보지 않는다 */
    static List<Long> amounts(String cell) {
        List<Long> out = new ArrayList<>();
        Matcher m = AMOUNT.matcher(Normalizer.normalize(cell == null ? "" : cell, Normalizer.Form.NFKC));
        while (m.find()) {
            Long v = ExtractCheck.yen(m.group());
            if (v != null && v >= 100) out.add(v);
        }
        return out;
    }

    /**
     * 금액이 (plan 행, category 열) 칸에 있는가.
     * 행 이름 = 그 행에서 금액이 없는 칸들, 열 이름 = 그 열에서 금액이 없는 위쪽 칸들(머리글).
     * 칸 안의 두 번째 금액(괄호 속 소인 요금 등)이면 열 이름 대신 category 를 믿는다 — 열 머리글은 대인 쪽 이름이기 때문
     */
    static Binding bind(List<Table> tables, String plan, String category, long amount) {
        String p = ExtractCheck.canon(plan);
        String k = ExtractCheck.canon(category);
        Binding misplaced = null;
        for (Table t : tables) {
            List<List<String>> rows = t.rows();
            for (int r = 0; r < rows.size(); r++) {
                for (int c = 0; c < rows.get(r).size(); c++) {
                    List<Long> found = amounts(rows.get(r).get(c));
                    int at = found.indexOf(amount);
                    if (at < 0) continue;
                    String rowLabel = rowLabel(rows.get(r));
                    String colLabel = columnLabel(rows, r, c);
                    boolean planOk = p.isEmpty() || loose(ExtractCheck.canon(rowLabel), p) || loose(ExtractCheck.canon(colLabel), p);
                    // 인원 구분 이름이 표 안에 아예 없으면(표 위 제목 「大人／（　）内は小人」 같은 데 있으면) 칸으로는 확인할 수 없다 — 통과
                    boolean categoryOk = k.isEmpty() || at > 0 || loose(ExtractCheck.canon(colLabel), k) || loose(ExtractCheck.canon(rowLabel), k)
                            || !mentions(rows, k);
                    if (planOk && categoryOk) return new Binding(Fit.MATCH, rowLabel, colLabel);
                    if (misplaced == null) misplaced = new Binding(Fit.MISPLACED, rowLabel, colLabel);
                }
            }
        }
        return misplaced != null ? misplaced : new Binding(Fit.NOT_IN_TABLE, "", "");
    }

    /** 표의 이름 칸(금액이 없는 칸) 어딘가에 그 이름이 있는지 */
    private static boolean mentions(List<List<String>> rows, String name) {
        for (List<String> row : rows) {
            for (String cell : row) {
                if (!cell.isBlank() && amounts(cell).isEmpty() && loose(ExtractCheck.canon(cell), name)) return true;
            }
        }
        return false;
    }

    /** 한쪽이 다른 쪽을 품으면 같은 이름으로 본다 ("3日間" ⊂ "3日間コース", "大人" ⊂ "大人(12歳以上)") */
    private static boolean loose(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static String rowLabel(List<String> row) {
        List<String> parts = new ArrayList<>();
        for (String cell : row) if (amounts(cell).isEmpty() && !cell.isBlank()) parts.add(cell);
        return String.join(" ", parts);
    }

    private static String columnLabel(List<List<String>> rows, int r, int c) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < r; i++) {
            if (c >= rows.get(i).size()) continue;
            String cell = rows.get(i).get(c);
            if (amounts(cell).isEmpty() && !cell.isBlank() && !parts.contains(cell)) parts.add(cell);
        }
        return String.join(" ", parts);
    }
}
