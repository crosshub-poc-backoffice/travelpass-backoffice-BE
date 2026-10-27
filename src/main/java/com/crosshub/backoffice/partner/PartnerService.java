package com.crosshub.backoffice.partner;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.partner.dto.PartnerResponse;
import com.crosshub.backoffice.product.ProductRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업체 조회. 스코프는 products와 동일한 하이브리드 전략:
 * crosshub는 전체, affiliate는 대행 업체(Caller.partnerIds), partner는 본인 업체만.
 */
@Service
public class PartnerService {

    private final PartnerRepository partners;
    private final ProductRepository products;
    private final AffiliateRepository affiliates;

    public PartnerService(PartnerRepository partners, ProductRepository products, AffiliateRepository affiliates) {
        this.partners = partners;
        this.products = products;
        this.affiliates = affiliates;
    }

    /** 운영방식(대행 연맹) 선택지 — id·이름만, 이름순 */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<java.util.Map<String, String>> affiliateOptions() {
        return affiliates.findAll(org.springframework.data.domain.Sort.by("name")).stream()
                .map(a -> java.util.Map.of("id", a.getId(), "name", a.getName())).toList();
    }

    @Transactional(readOnly = true)
    public List<PartnerResponse> list(Caller caller) {
        List<Partner> rows = caller.isCrosshub()
                ? partners.findAll(Sort.by(Sort.Direction.ASC, "name"))
                : partners.findByIdInOrderByNameAsc(caller.partnerIdsForQuery());
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PartnerResponse get(Caller caller, String id) {
        Partner p = partners.findById(id).orElseThrow(() -> notFound(id));
        if (!caller.canAccessPartner(p.getId())) {
            throw notFound(id);
        }
        return toResponse(p);
    }

    private PartnerResponse toResponse(Partner p) {
        long hidden = products.countByPartnerIdAndHiddenByPartnerOffTrue(p.getId());
        return PartnerResponse.from(p, hidden);
    }


    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "업체를 찾을 수 없습니다: " + id);
    }
}
