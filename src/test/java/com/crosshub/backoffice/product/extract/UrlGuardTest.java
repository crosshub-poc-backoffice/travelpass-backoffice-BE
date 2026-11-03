package com.crosshub.backoffice.product.extract;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SSRF: 서버가 대신 여는 주소가 내부망을 가리키면 막는다. DNS 를 타지 않게 IP 주소로만 확인한다 */
class UrlGuardTest {

    @Test
    void 내부_주소는_막는다() {
        for (String url : new String[] {
                "http://127.0.0.1/", "http://localhost/", "http://10.0.0.5/", "http://172.16.3.4/", "http://192.168.0.1/",
                "http://169.254.169.254/latest/meta-data/", "http://100.64.1.1/", "http://0.0.0.0/", "http://[::1]/",
                "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/"}) {
            assertThatThrownBy(() -> UrlGuard.check(url)).as(url).isInstanceOf(UrlGuard.BlockedException.class);
        }
    }

    @Test
    void 스킴_포트_계정정보() {
        assertThatThrownBy(() -> UrlGuard.check("file:///etc/passwd")).isInstanceOf(UrlGuard.BlockedException.class);
        assertThatThrownBy(() -> UrlGuard.check("ftp://8.8.8.8/")).isInstanceOf(UrlGuard.BlockedException.class);
        assertThatThrownBy(() -> UrlGuard.check("http://8.8.8.8:8080/")).isInstanceOf(UrlGuard.BlockedException.class);
        assertThatThrownBy(() -> UrlGuard.check("http://user:pw@8.8.8.8/")).isInstanceOf(UrlGuard.BlockedException.class);
        assertThatThrownBy(() -> UrlGuard.check("not a url")).isInstanceOf(UrlGuard.BlockedException.class);
    }

    @Test
    void 공인_주소는_통과() {
        assertThat(UrlGuard.check("https://8.8.8.8/archives/tour/x").getHost()).isEqualTo("8.8.8.8");
        assertThat(UrlGuard.allowed("data:image/png;base64,AAAA")).isTrue();
        assertThat(UrlGuard.allowed("http://192.168.0.1/x.png")).isFalse();
    }
}
