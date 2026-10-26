package com.crosshub.backoffice.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.product.dto.ProductStatusRequest;
import com.crosshub.backoffice.product.dto.ProductUpsertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 보안 리뷰: 노출중·숨김 상품은 업체가 BE를 직접 불러도 바로 못 바꾼다(변경요청 → 크로스허브 승인만).
 * 예전엔 이 규칙이 FE에만 있어서 반려된 상품(숨김)을 업체가 노출중으로 올릴 수 있었다.
 */
class ProductEditGuardTest {

    static final Caller PARTNER_A = new Caller("u-a", "partner-a", "partner", "pA", null, List.of("pA"));
    static final Caller AFFILIATE_X = new Caller("u-x", "fed-x", "affiliate", null, "affX", List.of("pA"));
    static final Caller CROSSHUB = new Caller("u-c", "crosshub", "crosshub", null, null, List.of());

    ProductRepository repo = mock(ProductRepository.class);
    PartnerRepository partners = mock(PartnerRepository.class);
    AuditLogRepository audit = mock(AuditLogRepository.class);
    ProductService service = new ProductService(repo, partners, audit);

    Product mine(String status) {
        Product p = mock(Product.class);
        when(p.getId()).thenReturn("prodA");
        when(p.getPartnerId()).thenReturn("pA");
        when(p.getStatus()).thenReturn(status);
        when(repo.findById("prodA")).thenReturn(Optional.of(p));
        Partner partner = mock(Partner.class);
        when(partner.isActive()).thenReturn(true);
        when(partner.getCommissionRate()).thenReturn(10);
        when(partners.findById("pA")).thenReturn(Optional.of(partner));
        return p;
    }

    static ProductUpsertRequest upsert() {
        return new ProductUpsertRequest("pA", "x", null, null, null, null, null, null, null,
                new ObjectMapper().createObjectNode(), null, null, null, null, null, null);
    }

    static int statusOf(Throwable e) {
        return e instanceof ProductException pe ? pe.status().value() : -1;
    }

    @Test
    void 반려된_숨김상품을_업체가_바로_노출하면_403() {
        Product p = mine("hidden");
        when(p.getReviewRejectReason()).thenReturn("사진 부족");
        for (Caller c : List.of(PARTNER_A, AFFILIATE_X)) {
            assertThat(statusOf(catchThrowable(() -> service.setStatus(c, "prodA", new ProductStatusRequest("live", null))))).isEqualTo(403);
        }
        verify(p, never()).setStatus(any());
        verify(audit, never()).save(any());
    }

    @Test
    void 노출중_상품을_업체가_바로_수정_숨김_삭제하면_403() {
        Product p = mine("live");
        for (Caller c : List.of(PARTNER_A, AFFILIATE_X)) {
            assertThat(statusOf(catchThrowable(() -> service.update(c, "prodA", upsert(), false)))).isEqualTo(403);
            assertThat(statusOf(catchThrowable(() -> service.setStatus(c, "prodA", new ProductStatusRequest("hidden", null))))).isEqualTo(403);
            assertThat(statusOf(catchThrowable(() -> service.delete(c, "prodA")))).isEqualTo(403);
        }
        verify(p, never()).setName(any());
        verify(p, never()).setStatus(any());
        verify(repo, never()).delete(any());
        verify(audit, never()).save(any());
    }

    @Test
    void 임시저장_심사중은_업체가_바로_고칠_수_있다() {
        mine("draft");
        assertThat(catchThrowable(() -> service.update(PARTNER_A, "prodA", upsert(), false))).isNull();
        mine("review");
        assertThat(catchThrowable(() -> service.update(PARTNER_A, "prodA", upsert(), false))).isNull();
    }

    @Test
    void 크로스허브는_노출중_숨김도_바로_바꾼다() {
        Product p = mine("hidden");
        assertThat(catchThrowable(() -> service.setStatus(CROSSHUB, "prodA", new ProductStatusRequest("live", null)))).isNull();
        verify(p).setStatus("live");
    }
}
