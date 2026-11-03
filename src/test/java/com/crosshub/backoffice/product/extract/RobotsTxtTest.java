package com.crosshub.backoffice.product.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsTxtTest {

    @Test
    void 별표_묶음과_가장_긴_규칙() {
        RobotsTxt r = RobotsTxt.parse("""
                User-agent: *
                Disallow: /wp-admin/
                Allow: /wp-admin/admin-ajax.php
                Disallow: /*.pdf$
                """, "TravelPassBackoffice");
        assertThat(r.allowed("/archives/tour/abc")).isTrue();
        assertThat(r.allowed("/wp-admin/options.php")).isFalse();
        assertThat(r.allowed("/wp-admin/admin-ajax.php")).isTrue();
        assertThat(r.allowed("/files/terms.pdf")).isFalse();
    }

    @Test
    void 우리_이름_묶음이_있으면_그걸_따른다() {
        RobotsTxt r = RobotsTxt.parse("""
                User-agent: *
                Disallow:

                User-agent: TravelPassBackoffice
                Disallow: /
                """, "TravelPassBackoffice");
        assertThat(r.allowed("/archives/tour/abc")).isFalse();
    }

    @Test
    void 빈_파일은_전부_허용() {
        assertThat(RobotsTxt.parse("", "TravelPassBackoffice").allowed("/")).isTrue();
    }
}
