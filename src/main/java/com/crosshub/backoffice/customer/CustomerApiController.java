package com.crosshub.backoffice.customer;

import com.crosshub.backoffice.customer.dto.CustomerDtos.CreateInquiry;
import com.crosshub.backoffice.customer.dto.CustomerDtos.InquiryView;
import com.crosshub.backoffice.customer.dto.CustomerDtos.ProductSummary;
import com.crosshub.backoffice.inquiry.CustomerInquiryService;
import com.crosshub.backoffice.product.ProductRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객용 API (TP 앱 / demo/tp-web). 고객 realm(tp) 토큰만 통과한다 (SecurityConfig: /api/customer/** → ROLE_CUSTOMER).
 * 문의는 로그인 필수 — 토큰 sub 에 연결되고, 조회는 "내 것"만. 예약·결제는 PoC 범위에서 뺐다 (고객은 상품을 보고 업체에 문의).
 *
 *   GET  /api/customer/products                  노출중 상품 목록
 *   GET  /api/customer/inquiries                 내 문의
 *   POST /api/customer/inquiries                 문의 접수
 *   GET  /api/customer/inquiries/{id}
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerApiController {

    private final CustomerInquiryService inquiries;
    private final ProductRepository products;

    public CustomerApiController(CustomerInquiryService inquiries, ProductRepository products) {
        this.inquiries = inquiries;
        this.products = products;
    }

    @GetMapping("/products")
    @Transactional(readOnly = true)
    public List<ProductSummary> products() {
        return products.findByStatusOrderByNameAsc("live").stream().map(ProductSummary::from).toList();
    }

    @GetMapping("/inquiries")
    public List<InquiryView> inquiries(@AuthenticationPrincipal Jwt jwt) {
        return inquiries.list(CustomerPrincipal.from(jwt));
    }

    @PostMapping("/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public InquiryView createInquiry(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateInquiry req) {
        return inquiries.create(CustomerPrincipal.from(jwt), req);
    }

    @GetMapping("/inquiries/{id}")
    public InquiryView inquiry(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return inquiries.get(CustomerPrincipal.from(jwt), id);
    }
}
