package com.crosshub.backoffice.product.extract;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 서버가 대신 여는 URL 의 SSRF 방어. 업체가 넣은 URL 로 서버가 내부망(localhost·사설 IP·클라우드 메타데이터)을 찌르지 못하게 한다.
 *
 * - http/https, 기본 포트(80·443)만. 주소에 계정 정보(user@host)가 있으면 거절
 * - 호스트 이름을 DNS 로 풀어 나온 주소가 하나라도 내부 주소면 거절 (A 레코드 여러 개 중 하나만 내부인 경우도 막는다)
 * - 첫 주소만이 아니라 브라우저가 따라가는 리다이렉트·하위 요청(이미지·스크립트)도 UrlSnapshotter 가 요청마다 여기로 다시 검사한다
 *
 * 한계: 검사와 브라우저의 실제 연결 사이에 DNS 응답이 바뀌는(DNS rebinding) 경우는 완전히 막지 못한다 — 네트워크 단에서 내부망 차단을 같이 두는 게 맞다.
 */
final class UrlGuard {

    private UrlGuard() {}

    static class BlockedException extends RuntimeException {
        BlockedException(String message) { super(message); }
    }

    /** 업체가 넣은 문자열 → 검사를 통과한 URI. 통과하지 못하면 BlockedException */
    static URI check(String raw) {
        URI uri;
        try {
            uri = URI.create(raw == null ? "" : raw.strip()).normalize();
        } catch (IllegalArgumentException e) {
            throw new BlockedException("URL 형식이 올바르지 않습니다.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new BlockedException("http 또는 https 주소만 넣을 수 있습니다.");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new BlockedException("URL 형식이 올바르지 않습니다.");
        if (uri.getRawUserInfo() != null) throw new BlockedException("계정 정보가 들어간 주소는 열 수 없습니다.");
        if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) throw new BlockedException("기본 포트(80·443) 주소만 열 수 있습니다.");
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new BlockedException("주소를 찾을 수 없습니다.");
        }
        for (InetAddress a : addresses) {
            if (internal(a)) throw new BlockedException("내부망 주소는 열 수 없습니다.");
        }
        return uri;
    }

    /** 브라우저 하위 요청용. data:·blob: 처럼 네트워크를 타지 않는 것은 통과 */
    static boolean allowed(String url) {
        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:")) return true;
        try {
            check(url);
            return true;
        } catch (BlockedException e) {
            return false;
        }
    }

    static boolean internal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            if (first == 0) return true;                                   // 0.0.0.0/8
            if (first == 100 && second >= 64 && second <= 127) return true; // 100.64/10 통신사 NAT
            if (first == 198 && (second == 18 || second == 19)) return true; // 198.18/15 벤치마크
            return first >= 224;                                           // 멀티캐스트·예약
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;                         // fc00::/7 ULA
            boolean mapped = true;                                         // ::ffff:a.b.c.d → IPv4 로 다시 본다
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            mapped &= (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
            if (mapped) {
                try {
                    return internal(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
