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
 * dev(crosshub)는 전체를 주고, 연맹 범위 필터는 FE가 건다. partner 계정은 본인 업체만.
 */
@Service
public class PartnerService {

    private final PartnerRepository partners;
    private final ProductRepository products;

    public PartnerService(PartnerRepository partners, ProductRepository products) {
        this.partners = partners;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<PartnerResponse> list(Caller caller) {
        List<Partner> rows = caller.isCrosshub()
                ? partners.findAll(Sort.by(Sort.Direction.ASC, "name"))
                : partners.findById(requirePartner(caller)).map(List::of).orElse(List.of());
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

    private static String requirePartner(Caller caller) {
        if (caller.partnerId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "업체 소속 계정이 아닙니다.");
        }
        return caller.partnerId();
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "업체를 찾을 수 없습니다: " + id);
    }
}
