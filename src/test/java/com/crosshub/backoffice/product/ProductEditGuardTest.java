package com.crosshub.backoffice.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.TestEntities;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 보안 리뷰: 노출중·숨김 상품은 업체가 BE를 직접 불러도 바로 못 바꾼다(변경요청 → 크로스허브 승인만).
 * 예전엔 이 규칙이 FE에만 있어서 반려된 상품(숨김)을 업체가 노출중으로 올릴 수 있었다.
 * 규칙은 Product 엔티티에 있으므로 진짜 엔티티로 서비스까지 통과시켜 본다.
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
        Product p = TestEntities.product("prodA", "pA", status);
        when(repo.findById("prodA")).thenReturn(Optional.of(p));
        Partner partner = mock(Partner.class);
        when(partner.isActive()).thenReturn(true);
        when(partner.getCommissionRate()).thenReturn(10);
        when(partners.findById("pA")).thenReturn(Optional.of(partner));
        return p;
    }

    static ProductUpsertRequest upsert() {
        return new ProductUpsertRequest("pA", "바뀐 이름", null, null, null, null, null, null, null,
                new ObjectMapper().createObjectNode(), null, null, null, null, null, null);
    }

    static int statusOf(Throwable e) {
        return e instanceof ProductException pe ? pe.status().value() : -1;
    }

    @Test
    void 반려된_숨김상품을_업체가_바로_노출하면_403() {
        Product p = mine("hidden");
        ReflectionTestUtils.setField(p, "reviewRejectReason", "사진 부족");
        for (Caller c : List.of(PARTNER_A, AFFILIATE_X)) {
            assertThat(statusOf(catchThrowable(() -> service.setStatus(c, "prodA", new ProductStatusRequest("live", null))))).isEqualTo(403);
        }
        assertThat(p.getStatus()).isEqualTo("hidden");
        assertThat(p.getReviewRejectReason()).isEqualTo("사진 부족");
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
        assertThat(p.getStatus()).isEqualTo("live");
        assertThat(p.getName()).isEqualTo("상품 prodA");
        verify(repo, never()).delete(any());
        verify(audit, never()).save(any());
    }

    @Test
    void 임시저장_심사중은_업체가_바로_고칠_수_있다() {
        Product draft = mine("draft");
        assertThat(catchThrowable(() -> service.update(PARTNER_A, "prodA", upsert(), true))).isNull();
        assertThat(draft.getStatus()).isEqualTo("review"); // submit → 심사중

        Product review = mine("review");
        assertThat(catchThrowable(() -> service.update(PARTNER_A, "prodA", upsert(), false))).isNull();
        assertThat(review.getName()).isEqualTo("바뀐 이름");
    }

    @Test
    void 크로스허브는_숨김을_바로_노출하고_반려사유를_지운다() {
        Product p = mine("hidden");
        ReflectionTestUtils.setField(p, "reviewRejectReason", "사진 부족");
        assertThat(catchThrowable(() -> service.setStatus(CROSSHUB, "prodA", new ProductStatusRequest("live", null)))).isNull();
        assertThat(p.getStatus()).isEqualTo("live");
        assertThat(p.getReviewRejectReason()).isNull();
    }

    // ── 엔티티 규칙만 따로 (서비스·mock 없이) ──

    @Test
    void 심사중에서_나가는건_크로스허브만_반려는_사유필수() {
        Product p = TestEntities.product("x", "pA", "review");
        assertThat(statusOf(catchThrowable(() -> p.changeStatus(false, "live", null, null)))).isEqualTo(403);
        assertThat(statusOf(catchThrowable(() -> p.changeStatus(true, "hidden", " ", null)))).isEqualTo(400);
        assertThat(p.changeStatus(true, "hidden", " 사진 부족 ", null)).isTrue();
        assertThat(p.getReviewRejectReason()).isEqualTo("사진 부족");
    }

    @Test
    void 노출은_업체가_비활성이거나_수수료율이_없으면_409() {
        Partner inactive = mock(Partner.class);
        Partner noCommission = mock(Partner.class);
        when(noCommission.isActive()).thenReturn(true);
        when(noCommission.getCommissionRate()).thenReturn(null); // mockito는 Integer 기본값을 0으로 줘서 명시
        Product p = TestEntities.product("x", "pA", "hidden");
        assertThat(statusOf(catchThrowable(() -> p.changeStatus(true, "live", null, null)))).isEqualTo(409);
        assertThat(statusOf(catchThrowable(() -> p.changeStatus(true, "live", null, inactive)))).isEqualTo(409);
        assertThat(statusOf(catchThrowable(() -> p.changeStatus(true, "live", null, noCommission)))).isEqualTo(409);
        assertThat(p.getStatus()).isEqualTo("hidden");
    }

    @Test
    void 업체_끄고_켜기() {
        Product p = TestEntities.product("x", "pA", "live");
        p.hideForPartnerOff();
        assertThat(p.getStatus()).isEqualTo("hidden");
        assertThat(p.isHiddenByPartnerOff()).isTrue();
        p.restoreAfterPartnerOn();
        assertThat(p.getStatus()).isEqualTo("live");
        assertThat(p.isHiddenByPartnerOff()).isFalse();
    }
}
