package com.crosshub.backoffice.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.TestEntities;
import com.crosshub.backoffice.TestServices;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.notification.Notifier;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.product.ProductService.Origin;
import com.crosshub.backoffice.product.change.ProductChangeRequest;
import com.crosshub.backoffice.product.change.ProductChangeRequestRepository;
import com.crosshub.backoffice.product.dto.ProductStatusRequest;
import com.crosshub.backoffice.product.translation.ProductTranslationService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 상품 쓰기에 붙은 알림·번역 완료 체크·크로스허브 직접처리 시 변경요청 자동 반려 (FE 에서 옮겨온 뒤처리) */
class ProductNotifyTest {

    static final Caller PARTNER_A = new Caller("u-a", "partner-a", "partner", "pA", null, List.of("pA"));
    static final Caller CROSSHUB = new Caller("u-c", "crosshub", "crosshub", null, null, List.of());

    ProductRepository repo = mock(ProductRepository.class);
    PartnerRepository partners = mock(PartnerRepository.class);
    AuditLogRepository audit = mock(AuditLogRepository.class);
    ProductChangeRequestRepository requests = mock(ProductChangeRequestRepository.class);
    ProductTranslationService translations = TestServices.translationsComplete();
    Notifier notifier = mock(Notifier.class);
    ProductService service = TestServices.productService(repo, partners, audit, requests, translations, notifier);

    Product mine(String status) {
        Product p = TestEntities.product("prodA", "pA", status);
        when(repo.findById("prodA")).thenReturn(Optional.of(p));
        Partner partner = mock(Partner.class);
        when(partner.isActive()).thenReturn(true);
        when(partners.findById("pA")).thenReturn(Optional.of(partner));
        when(notifier.partnerStakeholders("pA", false, true, true)).thenReturn(List.of("u-p", "u-aff"));
        return p;
    }

    @Test
    void 심사_통과하면_소유측에_product_status_changed() {
        mine("review");
        service.setStatus(CROSSHUB, "prodA", new ProductStatusRequest("live", null));
        verify(notifier).notify(eq(List.of("u-p", "u-aff")), eq("product_status_changed"), eq("prodA:live"), any());
    }

    @Test
    void 번역이_덜_됐으면_노출_못_하고_translationMissing() {
        Product p = mine("review");
        when(translations.complete(any())).thenReturn(false);
        Throwable e = catchThrowable(() -> service.setStatus(CROSSHUB, "prodA", new ProductStatusRequest("live", null)));
        assertThat(((ProductException) e).code()).isEqualTo("translationMissing");
        assertThat(p.getStatus()).isEqualTo("review");
        verify(notifier, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 크로스허브가_노출중_상품을_직접_숨기면_대기중_요청은_자동_반려되고_직접처리_알림() {
        Product p = mine("live");
        ProductChangeRequest pending = ProductChangeRequest.pending("prodA", "edit", null, "{}", "u-p");
        when(requests.findFirstByProductIdAndStatus("prodA", "pending")).thenReturn(Optional.of(pending));

        service.setStatus(CROSSHUB, "prodA", new ProductStatusRequest("hidden", null));

        assertThat(p.getStatus()).isEqualTo("hidden");
        assertThat(pending.getStatus()).isEqualTo("rejected");
        verify(requests).save(pending);
        verify(notifier).notify(eq(List.of("u-p", "u-aff")), eq("product_change_resolved"), eq(pending.getId() + ":rejected"), any());
        verify(notifier).notify(eq(List.of("u-p", "u-aff")), eq("product_direct_change"), anyString(), any());
    }

    @Test
    void 변경요청_승인으로_반영될_때는_자동반려도_직접처리_알림도_없다() {
        Product p = mine("live");
        service.setStatus(CROSSHUB, "prodA", new ProductStatusRequest("hidden", null), Origin.VIA_REQUEST);
        assertThat(p.getStatus()).isEqualTo("hidden");
        verify(requests, never()).findFirstByProductIdAndStatus(any(), any());
        verify(notifier, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 업체가_심사중_상품을_고치는_건_알림_대상이_아니다() {
        mine("review");
        service.setStatus(PARTNER_A, "prodA", new ProductStatusRequest("review", null));
        verify(notifier, never()).notify(any(), any(), any(), any());
    }
}
