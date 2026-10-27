package com.crosshub.backoffice.storage;

import java.util.regex.Pattern;

/**
 * DB 에는 저장소 안의 경로(key, 예: products/3f2c….png)만 넣고, API 응답에서 공개 URL 로 바꿔 준다.
 * 저장소·CDN 주소가 바뀌어도 S3_PUBLIC_URL 만 고치면 된다 — DB 를 고치지 않는다.
 *
 * - 응답: url(key) — 공개 URL 앞부분(ObjectStorage 가 시작할 때 넣는다) + "/" + key
 * - 저장: key(값, prefix) — 화면은 업로드 때 받은 URL 을 그대로 돌려보낸다. 우리 저장소 URL 이면 key 로 바꾸고, 아니면 null(거부)
 * - "/seed-images/…" 는 데모 시드가 쓰는 FE 정적 파일이라 그대로 둔다
 *
 * DTO 의 정적 팩토리(ProductDetailResponse.from 등)에서 부르려고 정적 클래스로 둔다.
 */
public final class StoredFiles {

    private StoredFiles() {}

    public static final String PRODUCT_IMAGES = "products";
    public static final String PARTNER_LICENSES = "partners";

    private static final Pattern SEED = Pattern.compile("^/seed-images/[\\w\\-.]+$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9-]+\\.[a-z0-9]{2,5}$");

    private static volatile String publicUrl = null;

    static void configure(String url) {
        publicUrl = url;
    }

    /** 저장된 값 → 화면에 줄 URL. 시드 경로·예전 전체 URL 은 그대로 */
    public static String url(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        if (stored.startsWith("/") || stored.startsWith("http://") || stored.startsWith("https://")) return stored;
        return publicUrl == null ? stored : publicUrl + "/" + stored;
    }

    /** 화면이 보낸 값 → DB 에 넣을 key. 우리 저장소의 prefix/ 아래 파일이 아니면 null */
    public static String key(String value, String prefix) {
        if (value == null) return null;
        String v = value.trim();
        if (SEED.matcher(v).matches()) return v;
        if (publicUrl != null && v.startsWith(publicUrl + "/")) v = v.substring(publicUrl.length() + 1);
        if (!v.startsWith(prefix + "/")) return null;
        return NAME.matcher(v.substring(prefix.length() + 1)).matches() ? v : null;
    }
}
