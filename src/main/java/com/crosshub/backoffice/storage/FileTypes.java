package com.crosshub.backoffice.storage;

import java.util.Locale;
import java.util.Map;

/**
 * 올라온 파일이 실제로 무엇인지 앞부분(매직 넘버)으로 확인한다 — 확장자·content-type 만 믿지 않는다.
 * FE lib/storage.ts 를 옮긴 것. 종류마다 받는 형식·최대 크기가 다르다.
 */
public final class FileTypes {

    private FileTypes() {}

    public static final Map<String, String> IMAGE = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    public static final long IMAGE_MAX_BYTES = 5L * 1024 * 1024;

    /** 사업자등록증. 사진(JPG·PNG) 또는 PDF */
    public static final Map<String, String> LICENSE = Map.of("image/jpeg", "jpg", "image/png", "png", "application/pdf", "pdf");
    public static final long LICENSE_MAX_BYTES = 10L * 1024 * 1024;

    /** 상품 소개서. 50MB 는 Upstage 상한에 맞춘 값 */
    public static final Map<String, String> DOCUMENT = Map.of(
            "application/pdf", "pdf",
            "application/haansofthwp", "hwp",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx",
            "image/jpeg", "jpg",
            "image/png", "png");
    public static final long DOCUMENT_MAX_BYTES = 50L * 1024 * 1024;

    public static String image(byte[] b) {
        if (starts(b, 0xff, 0xd8)) return "image/jpeg";
        if (starts(b, 0x89, 0x50, 0x4e, 0x47)) return "image/png";
        if (starts(b, 0x52, 0x49, 0x46, 0x46) && b.length >= 12 && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) return "image/webp";
        return null;
    }

    public static String license(byte[] b) {
        if (starts(b, 0x25, 0x50, 0x44, 0x46)) return "application/pdf"; // %PDF
        String image = image(b);
        return image != null && LICENSE.containsKey(image) ? image : null;
    }

    /**
     * OOXML(docx·xlsx·pptx)·HWPX 는 전부 ZIP 이라 앞부분만으로는 구분되지 않는다.
     * Upstage 는 content-type 이 아니라 파일 내용을 보고 처리하므로, ZIP·OLE 는 확장자로 종류를 정한다
     */
    public static String document(byte[] b, String filename) {
        if (starts(b, 0x25, 0x50, 0x44, 0x46)) return "application/pdf";
        String image = image(b);
        if (image != null && DOCUMENT.containsKey(image)) return image;

        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (starts(b, 0xd0, 0xcf, 0x11, 0xe0)) return ext.equals("hwp") ? "application/haansofthwp" : null; // 옛 한글·옛 오피스(OLE)
        if (starts(b, 0x50, 0x4b, 0x03, 0x04)) {
            return switch (ext) {
                case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "hwpx" -> "application/haansofthwp";
                default -> null;
            };
        }
        return null;
    }

    private static boolean starts(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[i] & 0xff) != sig[i]) return false;
        return true;
    }
}
