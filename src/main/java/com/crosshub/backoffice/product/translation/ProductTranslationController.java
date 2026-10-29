package com.crosshub.backoffice.product.translation;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.product.ProductBackgroundJobs;
import com.crosshub.backoffice.product.ProductException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 번역 쓰기 API. 상품을 수정할 수 있는 사람(업체 범위 안)이 번역도 고친다. 변경요청 없이 바로 반영.
 *  GET  /api/v1/products/{id}/translations                      번역 화면 (원문 항목·언어별 번역·빈 칸/원문변경 칸)
 *  GET  /api/v1/products/translations?locale=&ids=a,b            상품들의 한 언어 번역 원본 (대시보드·문의 화면 상품명 바꾸기용)
 *  POST /api/v1/products/{id}/translations/sync                 비었거나 원문이 바뀐 칸을 백그라운드로 번역 (202)
 *  POST /api/v1/products/{id}/translations/detect-source        원문 언어가 비었으면 감지해 저장 → { sourceLocale }
 *  POST /api/v1/products/{id}/translations/{locale}/retranslate 그 언어 전부 다시 번역 (동기 — 끝나면 204)
 *  PUT  /api/v1/products/{id}/translations/{locale}             { edits: {키: 글}, review } 사람이 고친 번역 저장
 *  PUT  /api/v1/products/{id}/source-locale                     { locale } 원문 언어 바로잡기 (+ 백그라운드 번역)
 *  실패 응답: {code} — notFound·translateFailed·translationIncomplete·tooLong
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductTranslationController {

    public record SaveRequest(Map<String, String> edits, Boolean review) {}
    public record LocaleRequest(String locale) {}

    private final ProductTranslationService service;
    private final ProductTranslationReadService reads;
    private final ProductBackgroundJobs jobs;
    private final CallerService callers;

    public ProductTranslationController(ProductTranslationService service, ProductTranslationReadService reads,
                                        ProductBackgroundJobs jobs, CallerService callers) {
        this.service = service;
        this.reads = reads;
        this.jobs = jobs;
        this.callers = callers;
    }

    /** 상품들의 한 언어 번역 원본. ids 는 쉼표로 (최대 200개). 매핑(/translations 가 {id} 보다 먼저 잡히게 고정 경로) */
    @org.springframework.web.bind.annotation.GetMapping("/translations")
    public java.util.List<ProductTranslationReadService.Row> rows(
            @org.springframework.web.bind.annotation.RequestParam String locale,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String ids) {
        java.util.List<String> list = java.util.Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().limit(200).toList();
        return reads.rows(callers.current(), list, locale);
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}/translations")
    public ProductTranslationReadService.TranslationsView view(@PathVariable String id) {
        return reads.view(callers.current(), id);
    }

    @PostMapping("/{id}/translations/sync")
    public ResponseEntity<Void> sync(@PathVariable String id) {
        service.requireScoped(callers.current(), id);   // 범위 밖·없는 상품이면 404. 번역은 뒤에서
        jobs.translate(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/translations/detect-source")
    public Map<String, String> detectSource(@PathVariable String id) {
        Map<String, String> body = new HashMap<>();
        body.put("sourceLocale", service.detectSource(callers.current(), id));
        return Collections.unmodifiableMap(body);
    }

    @PostMapping("/{id}/translations/{locale}/retranslate")
    public ResponseEntity<Void> retranslate(@PathVariable String id, @PathVariable String locale) {
        service.retranslate(callers.current(), id, locale);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/translations/{locale}")
    public ResponseEntity<Void> save(@PathVariable String id, @PathVariable String locale, @RequestBody SaveRequest req) {
        service.save(callers.current(), id, locale, req.edits(), Boolean.TRUE.equals(req.review()));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/source-locale")
    public ResponseEntity<Void> changeSourceLocale(@PathVariable String id, @RequestBody LocaleRequest req) {
        service.changeSourceLocale(callers.current(), id, req.locale());
        jobs.translate(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ProductException.class)
    public ResponseEntity<Map<String, String>> onError(ProductException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }
}
