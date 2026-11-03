package com.crosshub.backoffice.product.extract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Document Parse 가 돌려준 markdown 을 Solar 에 넣기 전에 정리한다 (상품 추출 전처리).
 * OCR 결과는 줄이 문장 중간에서 끊기고, 장식 기호·같은 줄이 반복된다. 그대로 넣으면 모델이 노이즈를 내용으로 착각하고 토큰도 더 든다.
 *
 * 한 줄씩 지나가는 필터 체인이다 (O(n)).
 *  1) NFKC — 전각 영숫자·반각 가타카나를 한 가지 표기로 (검산의 canon 과 같은 기준)
 *  2) 장식 기호 정리 — 기호만 있는 줄 삭제, 같은 기호가 3번 넘게 이어지면 지움.
 *     표의 점선 리더(ホテル ······ 港)는 "다음으로"라는 뜻이라 지우지 않고 → 로 바꾼다
 *  3) 끊긴 줄 잇기 — 앞 줄이 문장부호로 끝나지 않고 두 줄 다 일반 문단이면 붙인다.
 *     한글·라틴은 단어 사이에서 줄이 바뀌므로 공백을 넣고, 한자·가나는 띄어 쓰지 않으므로 그냥 붙인다
 *  4) 중복 줄 제거 — 정규화한 줄의 해시 집합. 표 행과 짧은 줄(大人 같은 칸 이름)은 반복이 정상이라 남긴다
 *  5) 빈 줄·공백 압축
 */
final class DocumentCleaner {

    private DocumentCleaner() {}

    /** Solar 에 넣는 문서 상한. 넘으면 뒤를 자른다 — 상품 정보는 앞쪽에 몰려 있고, 긴 약관이 토큰을 다 쓰는 걸 막는다 */
    static final int MAX_CHARS = 60_000;
    private static final int DEDUPE_MIN = 8;
    private static final int WRAP_MIN = 15;

    private static final Pattern SYMBOL_ONLY = Pattern.compile("^[\\p{So}\\p{Sm}\\p{Pd}\\p{Pc}\\p{Po}\\s*=_~|-]+$");
    private static final Pattern SYMBOL_RUN = Pattern.compile("([\\p{So}*=_~\\-・.])\\1{3,}");
    /** 점선 리더. 가운뎃점(U+00B7)·나카구로가 2개 이상, 또는 마침표 4개 이상(NFKC 가 …… 를 ...... 로 바꾼다). 줄 가운데 있을 때만 */
    private static final Pattern LEADER = Pattern.compile("(?<=\\S)\\s*(?:[·・]{2,}|\\.{4,})\\s*(?=\\S)");
    private static final Pattern DECORATION = Pattern.compile("[■□◆◇●○★☆▲△▼▽◎♪♫]+\\s*");
    private static final Pattern SENTENCE_END = Pattern.compile("[。．.!！?？:：」』）)]$");
    private static final Pattern BLOCK_START = Pattern.compile("^(#{1,6}\\s|[-*+・•]\\s?|\\d+[.)]\\s|\\||>|<)");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0\\u3000]+");

    static String clean(String markdown) {
        if (markdown == null) return "";
        String text = Normalizer.normalize(markdown, Normalizer.Form.NFKC).replace("\r\n", "\n");
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : text.split("\n", -1)) {
            String line = SPACES.matcher(raw).replaceAll(" ").strip();
            boolean table = line.startsWith("|");
            if (!table) {
                line = LEADER.matcher(line).replaceAll(" → ");
                line = SYMBOL_RUN.matcher(line).replaceAll("");
                line = DECORATION.matcher(line).replaceAll("").strip();
                if (!line.isEmpty() && SYMBOL_ONLY.matcher(line).matches()) line = "";
            }
            if (line.isEmpty()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
                continue;
            }
            if (!table && line.length() >= DEDUPE_MIN && !seen.add(dedupeKey(line))) continue;

            String prev = out.isEmpty() ? "" : out.get(out.size() - 1);
            if (joinable(prev, line)) {
                out.set(out.size() - 1, prev + (needsSpace(prev, line) ? " " : "") + line);
            } else {
                out.add(line);
            }
        }
        String joined = String.join("\n", out).strip();
        return joined.length() > MAX_CHARS ? joined.substring(0, MAX_CHARS) : joined;
    }

    /**
     * 앞 줄이 문장 중간에서 끊긴 일반 문단이고, 이번 줄도 새 블록(제목·목록·표)이 아니면 이어 붙인다.
     * 앞 줄이 짧으면(라벨·시간표 한 줄) 줄바꿈이 의도된 것이라 잇지 않는다 — OCR 이 끊는 건 줄 폭이 찬 긴 줄이다
     */
    static boolean joinable(String prev, String line) {
        if (prev.isEmpty() || prev.codePointCount(0, prev.length()) < WRAP_MIN) return false;
        if (prev.startsWith("#") || prev.startsWith("|") || prev.startsWith("<") || prev.startsWith(">")) return false;
        if (BLOCK_START.matcher(line).find()) return false;
        return !SENTENCE_END.matcher(prev).find();
    }

    /** 한자·가나 사이는 띄어 쓰지 않는다. 그 밖(한글·라틴·숫자)은 줄바꿈 자리가 단어 경계다 */
    static boolean needsSpace(String prev, String line) {
        int a = prev.codePointBefore(prev.length());
        int b = line.codePointAt(0);
        return !(cjk(a) || cjk(b));
    }

    private static boolean cjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA
                || (s == Character.UnicodeScript.COMMON && Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION);
    }

    private static String dedupeKey(String line) {
        return line.replaceAll("\\s", "");
    }
}
