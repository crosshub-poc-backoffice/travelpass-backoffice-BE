package com.crosshub.backoffice.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileTypesTest {

    private static byte[] bytes(int... b) {
        byte[] out = new byte[16];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    private static final byte[] JPEG = bytes(0xff, 0xd8, 0xff);
    private static final byte[] PNG = bytes(0x89, 0x50, 0x4e, 0x47);
    private static final byte[] WEBP = bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50);
    private static final byte[] PDF = bytes(0x25, 0x50, 0x44, 0x46);
    private static final byte[] ZIP = bytes(0x50, 0x4b, 0x03, 0x04);
    private static final byte[] OLE = bytes(0xd0, 0xcf, 0x11, 0xe0);

    @Test
    void 사진은_앞부분으로_판단한다() {
        assertThat(FileTypes.image(JPEG)).isEqualTo("image/jpeg");
        assertThat(FileTypes.image(PNG)).isEqualTo("image/png");
        assertThat(FileTypes.image(WEBP)).isEqualTo("image/webp");
        assertThat(FileTypes.image(PDF)).isNull();
        assertThat(FileTypes.image(new byte[1])).isNull();
    }

    @Test
    void 사업자등록증은_JPG_PNG_PDF만() {
        assertThat(FileTypes.license(PDF)).isEqualTo("application/pdf");
        assertThat(FileTypes.license(PNG)).isEqualTo("image/png");
        assertThat(FileTypes.license(WEBP)).isNull();
    }

    @Test
    void 소개서의_ZIP_OLE는_확장자로_종류를_정한다() {
        assertThat(FileTypes.document(PDF, "a.bin")).isEqualTo("application/pdf");
        assertThat(FileTypes.document(ZIP, "소개.XLSX")).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(FileTypes.document(ZIP, "a.hwpx")).isEqualTo("application/haansofthwp");
        assertThat(FileTypes.document(ZIP, "a.zip")).isNull();
        assertThat(FileTypes.document(OLE, "a.hwp")).isEqualTo("application/haansofthwp");
        assertThat(FileTypes.document(OLE, "a.doc")).isNull();
        assertThat(FileTypes.document(WEBP, "a.webp")).isNull();
    }
}
