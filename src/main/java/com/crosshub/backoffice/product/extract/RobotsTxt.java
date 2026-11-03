package com.crosshub.backoffice.product.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * robots.txt 해석 (RFC 9309 의 핵심만). 우리 이름(user-agent 토큰)에 맞는 묶음이 있으면 그걸, 없으면 "*" 묶음을 따른다.
 * 규칙끼리는 경로에 가장 길게 맞는 규칙이 이기고, 길이가 같으면 Allow 가 이긴다. * 는 아무 글자, 끝의 $ 는 경로 끝.
 */
final class RobotsTxt {

    private record Rule(boolean allow, String path, Pattern pattern) {}

    private final List<Rule> rules;

    private RobotsTxt(List<Rule> rules) {
        this.rules = rules;
    }

    static RobotsTxt parse(String text, String agentToken) {
        String me = agentToken.toLowerCase(Locale.ROOT);
        List<Rule> mine = new ArrayList<>(), any = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> current = new ArrayList<>();
        boolean readingAgents = false;
        boolean sawMine = false;
        for (String raw : (text == null ? "" : text).split("\n")) {
            String line = raw.replaceAll("#.*", "").strip();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (!readingAgents) {
                    flush(agents, current, me, mine, any);
                    agents = new ArrayList<>();
                    current = new ArrayList<>();
                }
                readingAgents = true;
                agents.add(value.toLowerCase(Locale.ROOT));
                if (!value.equals("*") && me.contains(value.toLowerCase(Locale.ROOT))) sawMine = true;
            } else if (key.equals("allow") || key.equals("disallow")) {
                readingAgents = false;
                if (value.isEmpty()) continue; // "Disallow:" 빈 값 = 전부 허용
                current.add(new Rule(key.equals("allow"), value, toPattern(value)));
            } else {
                readingAgents = false;
            }
        }
        flush(agents, current, me, mine, any);
        return new RobotsTxt(sawMine ? mine : any);
    }

    private static void flush(List<String> agents, List<Rule> rules, String me, List<Rule> mine, List<Rule> any) {
        for (String a : agents) {
            if (a.equals("*")) any.addAll(rules);
            else if (me.contains(a)) mine.addAll(rules);
        }
    }

    private static Pattern toPattern(String path) {
        boolean anchored = path.endsWith("$");
        String body = anchored ? path.substring(0, path.length() - 1) : path;
        StringBuilder re = new StringBuilder("^");
        for (String part : body.split("\\*", -1)) {
            if (re.length() > 1) re.append(".*");
            re.append(Pattern.quote(part));
        }
        if (anchored) re.append("$");
        return Pattern.compile(re.toString());
    }

    /** path 는 경로+쿼리 (예: /archives/tour/abc?x=1) */
    boolean allowed(String path) {
        Rule best = null;
        for (Rule r : rules) {
            if (!r.pattern().matcher(path).find()) continue;
            if (best == null || r.path().length() > best.path().length()
                    || (r.path().length() == best.path().length() && r.allow())) best = r;
        }
        return best == null || best.allow();
    }
}
