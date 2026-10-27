package com.crosshub.backoffice.storage;

import com.crosshub.backoffice.account.CallerService;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 파일 업로드 (화면 내부용). multipart 로 file 하나를 받아 저장하고 공개 URL 을 돌려준다. FE /api/products/images·/api/partners/license 를 옮긴 것.
 *  POST /api/v1/uploads/product-images     상품 사진 (JPG·PNG·WebP, 5MB)       → { url }
 *  POST /api/v1/uploads/partner-licenses   사업자등록증 (JPG·PNG·PDF, 10MB)    → { url }
 *  실패 응답: { error: file|tooLarge|imageType|licenseType, code: VALIDATION_ERROR } — FE 에서 쓰던 모양 그대로
 *
 * 상품·업체 저장과 별개로 먼저 올라가므로 저장을 취소하면 파일이 남을 수 있다 (PoC 에서는 그대로 둔다).
 * 상품·업체 메뉴는 세 역할 모두 수정할 수 있어서 로그인한 활성 계정이면 올릴 수 있다.
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final ObjectStorage storage;
    private final CallerService callers;

    public UploadController(ObjectStorage storage, CallerService callers) {
        this.storage = storage;
        this.callers = callers;
    }

    @PostMapping("/product-images")
    public ResponseEntity<Map<String, String>> productImage(@RequestParam(required = false) MultipartFile file) throws IOException {
        return upload(file, FileTypes.IMAGE_MAX_BYTES, FileTypes::image, "imageType", storage::putProductImage);
    }

    @PostMapping("/partner-licenses")
    public ResponseEntity<Map<String, String>> partnerLicense(@RequestParam(required = false) MultipartFile file) throws IOException {
        return upload(file, FileTypes.LICENSE_MAX_BYTES, FileTypes::license, "licenseType", storage::putPartnerLicense);
    }

    private ResponseEntity<Map<String, String>> upload(MultipartFile file, long maxBytes, Function<byte[], String> sniff,
                                                       String typeError, Put put) throws IOException {
        callers.current(); // 등록된 활성 계정인지 (아니면 401·403)
        if (file == null || file.isEmpty()) return invalid("file");
        if (file.getSize() > maxBytes) return invalid("tooLarge");
        byte[] bytes = file.getBytes();
        String mime = sniff.apply(bytes);
        if (mime == null) return invalid(typeError);
        return ResponseEntity.ok(Map.of("url", put.apply(bytes, mime)));
    }

    static ResponseEntity<Map<String, String>> invalid(String error) {
        return ResponseEntity.badRequest().body(Map.of("error", error, "code", "VALIDATION_ERROR"));
    }

    @FunctionalInterface
    private interface Put {
        String apply(byte[] bytes, String mime);
    }
}
