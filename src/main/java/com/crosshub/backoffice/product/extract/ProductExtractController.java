package com.crosshub.backoffice.product.extract;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.storage.FileTypes;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 상품 소개서·상품 페이지 → 등록 화면 초안 (화면 내부용). 두 입구 모두 "문서 하나"가 되어 ProductExtractService 로 간다.
 *  POST /api/v1/products/extract       multipart file (PDF·HWP·오피스·사진, 50MB) → { draft: 평면 JSON, sample }
 *  POST /api/v1/products/extract-url   { url } → 같은 응답. 서버가 페이지를 열어 PDF 로 인쇄한 뒤 읽는다
 *  실패 응답: 400 { error: file|tooLarge|documentType 또는 화면에 보일 문구, code: VALIDATION_ERROR } / 502 { error: 화면에 보일 문구, code: INTERNAL_ERROR }
 *
 * 올라온 원본·인쇄한 PDF 는 보관하지 않는다 — 추출에만 쓰고 버린다. DB 에는 업체가 초안을 확인하고 저장한 값만 들어간다.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductExtractController {

    private final ProductExtractService extractor;
    private final UrlSnapshotter snapshotter;
    private final CallerService callers;

    public ProductExtractController(ProductExtractService extractor, UrlSnapshotter snapshotter, CallerService callers) {
        this.extractor = extractor;
        this.snapshotter = snapshotter;
        this.callers = callers;
    }

    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract(@RequestParam(required = false) MultipartFile file) throws IOException {
        callers.current();
        if (file == null || file.isEmpty()) return invalid("file");
        if (file.getSize() > FileTypes.DOCUMENT_MAX_BYTES) return invalid("tooLarge");
        byte[] bytes = file.getBytes();
        String mime = FileTypes.document(bytes, file.getOriginalFilename());
        if (mime == null) return invalid("documentType");
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "document" : file.getOriginalFilename();
        return run(bytes, name);
    }

    public record UrlRequest(String url) {}

    @PostMapping("/extract-url")
    public ResponseEntity<Map<String, Object>> extractUrl(@RequestBody(required = false) UrlRequest req) {
        callers.current();
        if (req == null || req.url() == null || req.url().isBlank()) return invalid("url");
        if (extractor.usingSample()) return ResponseEntity.ok(Map.of("draft", extractor.extract(new byte[0], "page.pdf"), "sample", true));
        UrlSnapshotter.PageCapture page;
        try {
            page = snapshotter.capture(req.url());
        } catch (UrlSnapshotter.SnapshotException e) {
            return invalid(e.getMessage());
        }
        try {
            return ResponseEntity.ok(Map.of("draft", extractor.extractPage(page), "sample", extractor.usingSample()));
        } catch (ProductExtractService.ExtractException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage(), "code", "INTERNAL_ERROR"));
        }
    }

    private ResponseEntity<Map<String, Object>> run(byte[] bytes, String filename) {
        try {
            return ResponseEntity.ok(Map.of("draft", extractor.extract(bytes, filename), "sample", extractor.usingSample()));
        } catch (ProductExtractService.ExtractException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage(), "code", "INTERNAL_ERROR"));
        }
    }

    private static ResponseEntity<Map<String, Object>> invalid(String error) {
        return ResponseEntity.badRequest().body(Map.of("error", error, "code", "VALIDATION_ERROR"));
    }
}
