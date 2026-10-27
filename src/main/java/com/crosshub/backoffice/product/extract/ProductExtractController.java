package com.crosshub.backoffice.product.extract;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.common.AsyncConfig;
import com.crosshub.backoffice.storage.FileTypes;
import com.crosshub.backoffice.storage.ObjectStorage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 상품 소개서 업로드 → 등록 화면 초안 (화면 내부용). FE /api/products/extract 를 옮긴 것.
 *  POST /api/v1/products/extract   multipart file (PDF·HWP·오피스·사진, 50MB) → { draft: 뽑은 평면 JSON, sample }
 *  실패 응답: 400 { error: file|tooLarge|documentType, code: VALIDATION_ERROR } / 502 { error: 화면에 보일 문구, code: INTERNAL_ERROR }
 *
 * 원본은 무엇을 보고 채웠는지 남기려고 추출이 끝난 뒤 저장소에 같이 보관한다 (읽지 못한 문서까지 쌓아두지 않는다).
 * 화면은 이 주소를 쓰지 않는다. 뽑아낸 값이 이상할 때 원본을 열어보려고 로그에만 남긴다.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductExtractController {

    private static final Logger log = LoggerFactory.getLogger(ProductExtractController.class);

    private final ProductExtractClient extractor;
    private final ObjectStorage storage;
    private final CallerService callers;
    private final Executor background;

    public ProductExtractController(ProductExtractClient extractor, ObjectStorage storage, CallerService callers,
                                    @Qualifier(AsyncConfig.BACKGROUND) Executor background) {
        this.extractor = extractor;
        this.storage = storage;
        this.callers = callers;
        this.background = background;
    }

    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract(@RequestParam(required = false) MultipartFile file) throws IOException {
        callers.current();
        if (file == null || file.isEmpty()) return invalid("file");
        if (file.getSize() > FileTypes.DOCUMENT_MAX_BYTES) return invalid("tooLarge");
        byte[] bytes = file.getBytes();
        String mime = FileTypes.document(bytes, file.getOriginalFilename());
        if (mime == null) return invalid("documentType");

        try {
            Map<String, Object> body = Map.of("draft", extractor.extract(bytes, mime), "sample", extractor.usingSample());
            background.execute(() -> {
                try {
                    log.info("상품 소개서 추출: {}", storage.putProductDocument(bytes, mime));
                } catch (RuntimeException e) {
                    log.error("상품 소개서 보관 실패", e);
                }
            });
            return ResponseEntity.ok(body);
        } catch (ProductExtractClient.ExtractException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage(), "code", "INTERNAL_ERROR"));
        }
    }

    private static ResponseEntity<Map<String, Object>> invalid(String error) {
        return ResponseEntity.badRequest().body(Map.of("error", error, "code", "VALIDATION_ERROR"));
    }
}
