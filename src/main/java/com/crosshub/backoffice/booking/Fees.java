package com.crosshub.backoffice.booking;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * 수수료 계산. FE lib/fees.ts를 그대로 포트 — 숫자 하나도 다르면 안 되는 돈 계산이라
 * 로직을 새로 쓰지 않고 TS 원본의 식을 1:1로 옮긴다.
 */
public final class Fees {

    private Fees() {}

    public record Split(int commission, int net) {}

    /** 판매가를 업체 수수료율로 나눈다. 수수료는 반올림, 정산액은 나머지 */
    public static Split splitAmount(int amount, int commissionRate) {
        int commission = (int) Math.round((long) amount * commissionRate / 100.0); // int 곱셈은 약 2천만×100%에서 넘친다
        return new Split(commission, amount - commission);
    }

    /** 취소 수수료 규칙 한 줄: "출발 daysBefore일 전까지 취소하면 percent%" */
    public record CancelRule(int daysBefore, int percent) {}

    /** 취소 수수료율(%). 규칙은 daysBefore 내림차순으로 보고, 처음 daysLeft >= daysBefore인 줄. 없으면(그 이후 취소·노쇼) 100% */
    public static int cancelFeePercent(List<CancelRule> rules, long daysLeft) {
        return rules.stream()
                .sorted(Comparator.comparingInt(CancelRule::daysBefore).reversed())
                .filter(r -> daysLeft >= r.daysBefore())
                .findFirst()
                .map(CancelRule::percent)
                .orElse(100);
    }

    public static int cancelFeeFor(int amount, List<CancelRule> rules, long daysLeft) {
        return (int) Math.round((long) amount * cancelFeePercent(rules, daysLeft) / 100.0);
    }

    /** 일본 시간(JST, UTC+9) 기준 오늘 — 백오피스 업무 기준 시간대 */
    public static LocalDate jstToday() {
        return LocalDate.now(ZoneOffset.ofHours(9));
    }

    public static long daysBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }
}
