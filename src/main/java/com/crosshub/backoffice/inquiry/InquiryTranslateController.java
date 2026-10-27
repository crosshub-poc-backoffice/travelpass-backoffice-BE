package com.crosshub.backoffice.inquiry;

import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.product.translation.TranslateService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문의 화면의 "원문 자동번역 생성" 버튼. FE /api/translate 를 옮긴 것 — 번역 키는 BE 에만 둔다.
 *  POST /api/v1/inquiries/translate   { text, targetLang, sourceLang? } → { translatedText }
 *  언어는 문의에 저장된 한글 라벨(한국어·영어·…)로 받는다. 실패 응답: 400·502 { error: 화면에 보일 문구 }
 * 결과 저장은 따로 (POST /api/v1/inquiries/messages/{id}/translation). 로그인한 활성 계정만 — 열어두면 밖에서 우리 번역 비용으로 부를 수 있다.
 */
@RestController
public class InquiryTranslateController {

    /** 문의 언어 라벨 → 언어 코드. FE lib/inquiry-language.ts 의 라벨과 맞춘다 */
    static final Map<String, String> LABEL_TO_CODE = Map.of(
            "한국어", "ko", "영어", "en", "중국어", "zh-CN", "중국어(번체)", "zh-TW", "일본어", "ja", "베트남어", "vi");
    /** 문의 한 건에 충분한 길이. 더 길면 비용 남용으로 본다 */
    static final int MAX_CHARS = 10_000;

    public record TranslateRequest(String text, String targetLang, String sourceLang) {}

    private final TranslateService translator;
    private final CallerService callers;

    public InquiryTranslateController(TranslateService translator, CallerService callers) {
        this.translator = translator;
        this.callers = callers;
    }

    @PostMapping("/api/v1/inquiries/translate")
    public ResponseEntity<Map<String, String>> translate(@RequestBody TranslateRequest req) {
        callers.current();
        if (req.text() == null || req.text().isBlank() || req.targetLang() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "text와 targetLang은 필수입니다."));
        }
        if (req.text().length() > MAX_CHARS) return ResponseEntity.badRequest().body(Map.of("error", "번역할 글이 너무 깁니다."));
        String target = LABEL_TO_CODE.get(req.targetLang());
        if (target == null) return ResponseEntity.badRequest().body(Map.of("error", "지원하지 않는 언어입니다: " + req.targetLang()));
        String source = req.sourceLang() == null ? null : LABEL_TO_CODE.get(req.sourceLang());
        try {
            List<String> out = translator.translateMany(List.of(req.text()), target, source);
            return ResponseEntity.ok(Map.of("translatedText", out.isEmpty() ? "" : out.get(0)));
        } catch (TranslateService.TranslateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "번역 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요."));
        }
    }
}
