package com.crosshub.backoffice.inquiry;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.account.CallerService;
import com.crosshub.backoffice.common.PageResponse;
import com.crosshub.backoffice.inquiry.dto.InquiryDetailResponse;
import com.crosshub.backoffice.inquiry.dto.InquirySummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문의 조회 API.
 *  GET /api/v1/inquiries?status=&keyword=&page=   (partner는 본인 업체만)
 *  GET /api/v1/inquiries/{id}                      (대화 스레드 포함)
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

    @GetMapping("/{id}")
    public InquiryDetailResponse get(@PathVariable String id) {
        Caller caller = callers.current();
        return service.get(caller, id);
    }
}
