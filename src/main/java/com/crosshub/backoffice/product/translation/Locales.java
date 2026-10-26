package com.crosshub.backoffice.product.translation;

import java.util.List;
import java.util.Map;

/** 백오피스 화면 언어 6개 (FE i18n/routing.ts 와 같은 목록·순서). 상품 번역은 원문 언어를 뺀 나머지 5개에 만든다 */
public final class Locales {

    private Locales() {}

    public static final List<String> ALL = List.of("ko", "en", "ja", "zh-CN", "zh-TW", "vi");

    /** 모델에 넘길 언어 이름 */
    static final Map<String, String> NAME = Map.of(
            "ko", "Korean", "en", "English", "zh-CN", "Simplified Chinese",
            "zh-TW", "Traditional Chinese", "ja", "Japanese", "vi", "Vietnamese");

    public static boolean isLocale(String v) {
        return v != null && ALL.contains(v);
    }

    /** 원문 언어를 뺀 번역 대상 언어. 원문 언어를 모르면 전부 */
    public static List<String> targets(String sourceLocale) {
        return ALL.stream().filter(l -> !l.equals(sourceLocale)).toList();
    }

    /** 감지 결과를 화면 언어 코드로. zh 는 간체로 본다 */
    public static String normalize(String code) {
        if (code == null || code.isBlank()) return null;
        return "zh".equals(code) ? "zh-CN" : code;
    }
}
