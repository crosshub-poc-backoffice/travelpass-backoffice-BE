package com.crosshub.backoffice.inquiry;

import com.crosshub.backoffice.customer.CustomerPrincipal;
import com.crosshub.backoffice.customer.dto.CustomerDtos.CreateInquiry;
import com.crosshub.backoffice.customer.dto.CustomerDtos.InquiryView;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductRepository;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 고객의 "내 문의". customerId(토큰 sub) 로 범위를 건다. 접수 규칙은 FE 의 옛 createInquiryFromApp 과 동일 */
@Service
public class CustomerInquiryService {

    static final Set<String> LANGUAGES = Set.of("en", "zh", "ja", "vi");
    static final int QUESTION_MAX = 5000;

    private final InquiryRepository inquiries;
    private final ProductRepository products;

    public CustomerInquiryService(InquiryRepository inquiries, ProductRepository products) {
        this.inquiries = inquiries;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<InquiryView> list(CustomerPrincipal c) {
        return inquiries.findByCustomerIdOrderByCreatedAtDesc(c.id()).stream()
                .map(i -> InquiryView.from(i, i.getProduct() != null ? i.getProduct().getName() : null)).toList();
    }

    @Transactional(readOnly = true)
    public InquiryView get(CustomerPrincipal c, String id) {
        Inquiry i = mine(c, id);
        return InquiryView.from(i, i.getProduct() != null ? i.getProduct().getName() : null);
    }

    @Transactional
    public InquiryView create(CustomerPrincipal c, CreateInquiry req) {
        if (req.productId() == null || req.productId().isBlank() || req.question() == null || req.question().isBlank()
                || req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "required");
        }
        if (req.language() == null || !LANGUAGES.contains(req.language())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidLanguage");
        if (req.question().length() > QUESTION_MAX) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tooLong");

        var existing = inquiries.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            if (!c.id().equals(existing.get().getCustomerId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotencyKeyInUse");
            return get(c, existing.get().getId());
        }
        Product p = products.findById(req.productId()).filter(x -> "live".equals(x.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "productNotFound"));

        Inquiry i = Inquiry.newFromCustomer(p.getId(), c.id(), c.name(), req.language(), req.question().trim(), req.idempotencyKey());
        try {
            inquiries.saveAndFlush(i);
        } catch (DataIntegrityViolationException e) {
            var winner = inquiries.findByIdempotencyKey(req.idempotencyKey());
            if (winner.isPresent() && c.id().equals(winner.get().getCustomerId())) return get(c, winner.get().getId());
            throw e;
        }
        return InquiryView.from(i, p.getName());
    }

    private Inquiry mine(CustomerPrincipal c, String id) {
        return inquiries.findByIdAndCustomerId(id, c.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다: " + id));
    }
}
