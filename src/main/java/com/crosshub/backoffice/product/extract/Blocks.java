package com.crosshub.backoffice.product.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 정리된 문서를 블록(제목 단위 문단·표 하나·이미지 하나)으로 나누고 B1, B2 … 이름을 붙인다.
 *
 * 블록은 두 가지에 쓴다.
 *  1) 라우팅 — Solar 가 블록마다 라벨(요금·일정·숙박·무관 …)을 붙이고, 무관한 블록(메뉴·연락처)은 추출 입력에서 뺀다
 *  2) 누락 검사(재현율) — 추출한 항목마다 출처 블록을 적게 하고, 내용 블록인데 아무 항목도 가리키지 않으면 "빠졌다"고 본다.
 *     주제 키워드를 미리 정하지 않아서 사이트·언어가 달라도 같은 방식으로 동작한다
 */
final class Blocks {

    private Blocks() {}

    record Block(String id, String text) {}

    /** 블록 하나의 상한. 넘으면 빈 줄에서 나눈다 — 라벨이 한 블록에 여러 주제를 섞지 않게 */
    static final int MAX_CHARS = 1500;
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s");
    /** 이미지 OCR 결과의 시작 표시 (ProductExtractService.assemble 이 넣는다) */
    static final Pattern IMAGE_MARK = Pattern.compile("^\\[image \\d+]$");
    /** 이미지 OCR 결과의 끝 표시. 이미지 다음 글이 이미지 블록에 섞이지 않게 여기서 블록을 닫는다 */
    static final Pattern IMAGE_END = Pattern.compile("^\\[/image \\d+]$");

    static List<Block> split(String doc) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inTable = false;
        for (String line : (doc == null ? "" : doc).split("\n", -1)) {
            boolean table = line.startsWith("|");
            boolean startsNew = HEADING.matcher(line).find() || IMAGE_MARK.matcher(line.strip()).matches()
                    // 표가 시작되면 새 블록. 단, 바로 위가 짧은 제목·이미지 표시뿐이면 표와 한 블록으로 둔다 (「基本旅行代金」 + 표)
                    || (table && !inTable && !isCaption(cur))
                    || (!table && inTable);
            if (startsNew) {
                flush(chunks, cur);
            } else if (!table && line.isBlank() && cur.length() > MAX_CHARS / 2) {
                flush(chunks, cur);
                inTable = false;
                continue;
            } else if (!table && cur.length() > MAX_CHARS) {
                flush(chunks, cur);
            }
            if (IMAGE_END.matcher(line.strip()).matches()) {
                flush(chunks, cur);
                inTable = false;
                continue;
            }
            if (cur.length() > 0 || !line.isBlank()) cur.append(line).append('\n');
            inTable = table;
        }
        flush(chunks, cur);
        List<Block> out = new ArrayList<>();
        for (String c : chunks) out.add(new Block("B" + (out.size() + 1), c));
        return out;
    }

    /** 블록에 아직 짧은 제목·설명 한두 줄만 있는지 */
    private static boolean isCaption(StringBuilder cur) {
        String s = cur.toString().strip();
        return !s.isEmpty() && s.length() <= 80 && s.split("\n").length <= 2;
    }

    private static void flush(List<String> chunks, StringBuilder cur) {
        String s = cur.toString().strip();
        if (!s.isEmpty()) chunks.add(s);
        cur.setLength(0);
    }

    /** Solar 에 넣는 모양. 블록 이름을 줄로 표시한다 — 모델이 항목마다 출처 블록을 적을 수 있게 */
    static String render(List<Block> blocks) {
        return render(blocks, Map.of());
    }

    /** 라벨이 있으면 "<<B3 price>>" 처럼 함께 적는다 — 추출 단계가 블록의 성격을 보고 칸을 고르게 */
    static String render(List<Block> blocks, Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (sb.length() > 0) sb.append("\n\n");
            String label = labels.getOrDefault(b.id(), "");
            sb.append("<<").append(b.id()).append(label.isEmpty() ? "" : " " + label).append(">>\n").append(b.text());
        }
        return sb.toString();
    }

    static Map<String, Block> byId(List<Block> blocks) {
        Map<String, Block> out = new LinkedHashMap<>();
        for (Block b : blocks) out.put(b.id(), b);
        return out;
    }

    /** 사람에게 보여 줄 블록 앞부분 한 줄 (60자) */
    static String snippet(Block b) {
        for (String line : b.text().split("\n")) {
            String s = line.replace("|", " ").replaceAll("\\s+", " ").replaceAll("^#+\\s*", "").strip();
            if (s.isEmpty() || s.matches("[-: ]+") || IMAGE_MARK.matcher(line.strip()).matches()) continue;
            return s.length() > 60 ? s.substring(0, 60) + "…" : s;
        }
        return "";
    }
}
