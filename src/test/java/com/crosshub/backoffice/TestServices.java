package com.crosshub.backoffice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.UserAccountRepository;
import com.crosshub.backoffice.notification.Notifier;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.crosshub.backoffice.product.ProductBackgroundJobs;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.ProductService;
import com.crosshub.backoffice.product.change.ChangeRequestService;
import com.crosshub.backoffice.product.change.ProductChangeRequestRepository;
import com.crosshub.backoffice.product.translation.ProductTranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 테스트용 서비스 조립. 알림·번역·백그라운드 작업은 mock — 범위·상태 규칙 테스트에선 "불렸는지"만 본다.
 * 번역 완료는 기본 true (노출 전환 테스트가 translationMissing 에 막히지 않게). 막고 싶은 테스트는 직접 mock 을 넘긴다.
 */
public final class TestServices {

    private TestServices() {}

    public static ProductTranslationService translationsComplete() {
        ProductTranslationService t = mock(ProductTranslationService.class);
        when(t.complete(any())).thenReturn(true);
        return t;
    }

    public static ProductService productService(ProductRepository repo, PartnerRepository partners, AuditLogRepository audit) {
        return productService(repo, partners, audit, mock(ProductChangeRequestRepository.class), translationsComplete(), mock(Notifier.class));
    }

    public static ProductService productService(ProductRepository repo, PartnerRepository partners, AuditLogRepository audit,
                                                ProductChangeRequestRepository requests, ProductTranslationService translations, Notifier notifier) {
        return new ProductService(repo, partners, audit, requests, translations, notifier, mock(ProductBackgroundJobs.class),
                mock(com.crosshub.backoffice.product.translation.ProductTranslationReadService.class),
                mock(com.crosshub.backoffice.product.translation.ProductTranslationRepository.class),
                mock(ChangeRequestService.class), new ObjectMapper());
    }

    public static ChangeRequestService changeRequestService(ProductChangeRequestRepository requests, ProductRepository products,
                                                            ProductService productService, Notifier notifier) {
        return new ChangeRequestService(requests, products, productService, mock(UserAccountRepository.class), notifier, new ObjectMapper());
    }
}
