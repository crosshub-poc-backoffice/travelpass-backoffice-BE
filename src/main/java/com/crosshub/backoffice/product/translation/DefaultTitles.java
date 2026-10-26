package com.crosshub.backoffice.product.translation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 상품 등록 때 자동으로 넣는 기본 섹션 제목·금액 열 이름 (언어별). FE lib/products/default-titles.ts 와 같은 표.
 * 기본 제목은 번역 API 를 부르지 않고 이 표의 그 언어 값을 쓴다.
 */
public final class DefaultTitles {

    private DefaultTitles() {}

    public enum Kind { amountColumn, itinerary, program, transport, lodging }

    private static final Map<String, Map<Kind, String>> TITLES = new LinkedHashMap<>();

    static {
        TITLES.put("ko", Map.of(Kind.amountColumn, "금액", Kind.itinerary, "일정표", Kind.program, "프로그램", Kind.transport, "교통수단", Kind.lodging, "숙박 정보"));
        TITLES.put("en", Map.of(Kind.amountColumn, "Price", Kind.itinerary, "Itinerary", Kind.program, "Program", Kind.transport, "Transportation", Kind.lodging, "Accommodation"));
        TITLES.put("ja", Map.of(Kind.amountColumn, "金額", Kind.itinerary, "行程表", Kind.program, "プログラム", Kind.transport, "交通手段", Kind.lodging, "宿泊情報"));
        TITLES.put("zh-CN", Map.of(Kind.amountColumn, "金额", Kind.itinerary, "行程表", Kind.program, "活动项目", Kind.transport, "交通方式", Kind.lodging, "住宿信息"));
        TITLES.put("zh-TW", Map.of(Kind.amountColumn, "金額", Kind.itinerary, "行程表", Kind.program, "活動項目", Kind.transport, "交通方式", Kind.lodging, "住宿資訊"));
        TITLES.put("vi", Map.of(Kind.amountColumn, "Giá", Kind.itinerary, "Lịch trình", Kind.program, "Chương trình", Kind.transport, "Phương tiện", Kind.lodging, "Thông tin lưu trú"));
    }

    /** 어느 언어든 기본 제목이면 그 종류, 직접 쓴 제목이면 null */
    public static Kind kindOf(String title) {
        if (title == null) return null;
        for (Map<Kind, String> names : TITLES.values()) {
            for (Map.Entry<Kind, String> e : names.entrySet()) {
                if (e.getValue().equals(title)) return e.getKey();
            }
        }
        return null;
    }

    /** 기본 제목이면 그 언어의 값, 아니면 null (번역 API 로 보내야 함) */
    public static String translate(String title, String locale) {
        Kind kind = kindOf(title);
        if (kind == null) return null;
        Map<Kind, String> names = TITLES.getOrDefault(locale, TITLES.get("ko"));
        return names.get(kind);
    }
}
