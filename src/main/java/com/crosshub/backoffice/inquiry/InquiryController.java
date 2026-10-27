package com.crosshub.backoffice.inquiry;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.common.PageResponse;
import com.crosshub.backoffice.inquiry.dto.InquiryDetailResponse;
import com.crosshub.backoffice.inquiry.dto.InquirySummaryResponse;
import com.crosshub.backoffice.inquiry.dto.PostAnswerRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문의 API.
 *  GET  /api/v1/inquiries?status=&keyword=&page=   (partner는 본인 업체만)
 *  GET  /api/v1/inquiries/{id}                      (대화 스레드 포함)
 *  POST /api/v1/inquiries/{id}/answer   { text, textTranslated?, locale } 관리자 답변 등록
 *  POST /api/v1/inquiries/messages/{messageId}/translation   { textTranslated, locale } 메시지 번역 결과 저장
 */
@RestController
@RequestMapping("/api/v1/inquiries")
public class InquiryController {

    private final InquiryService service;
    private final CallerService callers;

    public InquiryController(InquiryService service, CallerService callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping
    public PageResponse<InquirySummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page) {
        Caller caller = callers.current();
        return service.list(caller, status, keyword, page);
    }

    @GetMapping("/pending")
    public InquiryService.PendingPage pending(@RequestParam(defaultValue = "5") int limit,
                                              @RequestParam(required = false) String partnerId) {
        return service.pending(callers.current(), limit, partnerId);
    }

    @GetMapping("/{id}")
    public InquiryDetailResponse get(@PathVariable String id) {
        Caller caller = callers.current();
        return service.get(caller, id);
    }

    public record TranslationRequest(String textTranslated, String locale) {}

    @PostMapping("/messages/{messageId}/translation")
    public org.springframework.http.ResponseEntity<Void> saveTranslation(@PathVariable String messageId, @RequestBody TranslationRequest req) {
        service.saveMessageTranslation(callers.current(), messageId, req.textTranslated(), req.locale());
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/answer")
    public InquiryDetailResponse postAnswer(@PathVariable String id, @RequestBody PostAnswerRequest req) {
        Caller caller = callers.current();
        return service.postAnswer(caller, id, req);
    }
}
