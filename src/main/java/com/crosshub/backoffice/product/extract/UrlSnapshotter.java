package com.crosshub.backoffice.product.extract;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 상품 페이지 URL → PDF. 업로드한 소개서와 같은 "문서 하나"로 만들어 뒤 단계(Document Parse → Solar)를 그대로 탄다 (어댑터).
 *
 * 글자만 긁지 않고 브라우저로 그려서 인쇄하는 이유: 여행사 페이지는 요금표·일정표가 이미지인 경우가 많다. PDF 에 이미지째 담아야 OCR 이 읽는다.
 *  1) UrlGuard 로 주소 검사(SSRF) → robots.txt 확인
 *  2) 헤드리스 Chromium 으로 열기. 이후 모든 요청(리다이렉트·이미지·스크립트)도 UrlGuard 로 다시 검사해 내부 주소면 끊는다
 *  3) 끝까지 스크롤해 지연 로딩 이미지를 띄우고, 머리말·꼬리말·메뉴를 숨긴 뒤 A4 로 인쇄 — 쪽 수가 곧 Parse 과금이다
 * PDF 는 메모리에서만 쓰고 저장하지 않는다. 브라우저는 무거워서 한 번에 하나만 돌린다.
 */
@Component
public class UrlSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(UrlSnapshotter.class);
    static final String AGENT_TOKEN = "TravelPassBackoffice";
    private static final int NAV_TIMEOUT_MS = 30_000;
    private static final int MAX_SCROLLS = 40;
    private static final String HIDE_CHROME = "header, footer, nav, [role=banner], [role=navigation], [role=contentinfo],"
            + " .header, .footer, .gnav, .breadcrumb, #header, #footer { display: none !important; }";

    public static class SnapshotException extends RuntimeException {
        public SnapshotException(String message) { super(message); }
    }

    private final String chromiumPath;
    private final String userAgent;
    private final Semaphore oneAtATime = new Semaphore(1);
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public UrlSnapshotter(@Value("${app.url-snapshot.chromium-path:}") String chromiumPath,
                          @Value("${app.url-snapshot.user-agent:TravelPassBackoffice/1.0}") String userAgent) {
        this.chromiumPath = chromiumPath == null ? "" : chromiumPath.strip();
        this.userAgent = userAgent;
    }

    /**
     * 페이지 한 장을 읽은 결과.
     *  - markdown: DOM 글자 그대로(표는 병합 칸을 편 격자). 본문 이미지 자리는 [[IMG n]]
     *  - imagesPdf: 본문 이미지들을 한 쪽에 하나씩 담은 PDF (n 번째 쪽 = [[IMG n]]). 이미지가 없으면 null
     *  - pagePdf: 글자를 거의 못 얻은 페이지(이미지·캔버스뿐)를 위한 예전 방식 — 페이지 전체를 인쇄한 PDF. 그 밖에는 null
     */
    public record PageCapture(String markdown, byte[] imagesPdf, int imageCount, byte[] pagePdf) {}

    /** DOM 에서 얻은 글자가 이보다 적으면 글자 대신 페이지 전체를 인쇄해 OCR 한다 */
    static final int MIN_TEXT = 300;
    private static final String PAGE_EXTRACT = resource("upstage/page-extract.js");

    private static String resource(String path) {
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(path + " 를 읽지 못했습니다", e);
        }
    }

    /**
     * URL → 글자(DOM) + 이미지(OCR 용 PDF). 글자는 HTML 에 있는 그대로라 OCR 오류가 없고 Parse 비용도 들지 않는다.
     * 이미지(요금표·일정표 그림)만 원본 크기(srcset 의 가장 큰 후보)로 모아 OCR 한다
     */
    public PageCapture capture(String rawUrl) {
        URI uri = guard(rawUrl);
        if (!oneAtATime.tryAcquire()) throw new SnapshotException("다른 페이지를 읽는 중입니다. 잠시 후 다시 시도해 주세요.");
        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true);
            if (!chromiumPath.isEmpty()) launch.setExecutablePath(Path.of(chromiumPath));
            try (Browser browser = pw.chromium().launch(launch);
                 BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setUserAgent(userAgent).setLocale("ja-JP")
                         .setViewportSize(1280, 900).setDeviceScaleFactor(2))) {
                ctx.route("**/*", route -> {
                    if (UrlGuard.allowed(route.request().url())) route.resume();
                    else route.abort();
                });
                Page page = ctx.newPage();
                page.setDefaultTimeout(NAV_TIMEOUT_MS);
                page.navigate(uri.toString(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(NAV_TIMEOUT_MS));
                scrollToBottom(page);
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> got = (java.util.Map<String, Object>) page.evaluate(PAGE_EXTRACT);
                String markdown = String.valueOf(got.getOrDefault("markdown", ""));
                int textLength = ((Number) got.getOrDefault("textLength", 0)).intValue();
                if (textLength < MIN_TEXT) {
                    log.info("DOM 글자가 적어({}자) 페이지 전체를 인쇄해 읽습니다: {}", textLength, uri);
                    return new PageCapture("", null, 0, printPage(page));
                }
                @SuppressWarnings("unchecked")
                List<java.util.Map<String, Object>> images = (List<java.util.Map<String, Object>>) got.getOrDefault("images", List.of());
                byte[] imagesPdf = images.isEmpty() ? null : imagesPdf(ctx, uri, images);
                return new PageCapture(markdown, imagesPdf, images.size(), null);
            }
        } catch (PlaywrightException e) {
            log.warn("URL 읽기 실패 ({}): {}", uri, e.getMessage());
            throw new SnapshotException("페이지를 열지 못했습니다. 주소를 확인하거나 PDF 로 저장해서 올려 주세요.");
        } finally {
            oneAtATime.release();
        }
    }

    /** 이미지들을 한 쪽에 하나씩 담은 PDF. Parse 를 한 번만 부르려고 묶는다(과금은 쪽 수 = 이미지 수).
     *  이미지 요청도 같은 컨텍스트라 UrlGuard 경로 검사를 그대로 받는다. 핫링크 차단에 대비해 원래 페이지를 Referer 로 보낸다 */
    private static byte[] imagesPdf(BrowserContext ctx, URI origin, List<java.util.Map<String, Object>> images) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset=utf-8><style>"
                + "@page{size:A4;margin:8mm}body{margin:0}div{page-break-after:always;text-align:center}"
                + "img{max-width:100%;max-height:270mm;object-fit:contain}</style></head><body>");
        for (java.util.Map<String, Object> img : images) {
            String url = String.valueOf(img.get("url")).replace("\"", "%22");
            html.append("<div><img src=\"").append(url).append("\"></div>");
        }
        html.append("</body></html>");
        Page page = ctx.newPage();
        page.setExtraHTTPHeaders(java.util.Map.of("Referer", origin.toString()));
        page.setContent(html.toString(), new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(NAV_TIMEOUT_MS));
        return page.pdf(new Page.PdfOptions().setFormat("A4").setPrintBackground(true));
    }

    private static byte[] printPage(Page page) {
        page.addStyleTag(new Page.AddStyleTagOptions().setContent(HIDE_CHROME));
        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(com.microsoft.playwright.options.Media.SCREEN));
        return page.pdf(new Page.PdfOptions().setFormat("A4").setPrintBackground(true));
    }

    private URI guard(String rawUrl) {
        URI uri;
        try {
            uri = UrlGuard.check(rawUrl);
        } catch (UrlGuard.BlockedException e) {
            throw new SnapshotException(e.getMessage());
        }
        if (!robotsAllow(uri)) throw new SnapshotException("이 사이트는 자동 수집을 허용하지 않습니다(robots.txt). PDF 로 저장해서 올려 주세요.");
        return uri;
    }

    /** 지연 로딩(lazy) 이미지는 화면에 들어와야 받는다 — 바닥까지 내려가며 기다린 뒤 맨 위로 */
    private static void scrollToBottom(Page page) {
        page.evaluate("document.querySelectorAll('img[loading=lazy]').forEach(i => i.loading = 'eager')");
        for (int i = 0; i < MAX_SCROLLS; i++) {
            Object done = page.evaluate("() => { window.scrollBy(0, window.innerHeight); "
                    + "return window.scrollY + window.innerHeight >= document.body.scrollHeight - 2; }");
            page.waitForTimeout(250);
            if (Boolean.TRUE.equals(done)) break;
        }
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5_000));
        } catch (PlaywrightException ignored) {
            // 계속 무언가를 받는 페이지(광고·채팅)는 조용해지지 않는다 — 그대로 인쇄한다
        }
        page.evaluate("window.scrollTo(0, 0)");
    }

    /** robots.txt 가 우리를 막는지. 파일이 없거나 못 읽으면 허용으로 본다 (RFC 9309: 4xx 는 제한 없음) */
    boolean robotsAllow(URI uri) {
        URI robots = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + "/robots.txt");
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(robots).timeout(Duration.ofSeconds(5))
                    .header("User-Agent", userAgent).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return true;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            return RobotsTxt.parse(res.body(), AGENT_TOKEN).allowed(path);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.info("robots.txt 를 읽지 못해 허용으로 봅니다 ({}): {}", robots, e.getMessage());
            return true;
        }
    }
}
