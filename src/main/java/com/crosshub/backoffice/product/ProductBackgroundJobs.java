package com.crosshub.backoffice.product;

import com.crosshub.backoffice.common.AfterCommit;
import com.crosshub.backoffice.common.AsyncConfig;
import com.crosshub.backoffice.product.inspect.InspectionService;
import com.crosshub.backoffice.product.translation.ProductTranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 상품 저장 뒤에 돌리는 백그라운드 작업 — 자동 번역, AI 검수. FE 가 Next.js after() 로 하던 것.
 * 호출한 트랜잭션이 커밋된 뒤에 시작한다 (AfterCommit). 실패해도 저장은 이미 끝났으니 로그만.
 */
@Component
public class ProductBackgroundJobs {

    private static final Logger log = LoggerFactory.getLogger(ProductBackgroundJobs.class);

    private final ProductTranslationService translations;
    private final InspectionService inspections;
    private final ProductBackgroundJobs self;

    public ProductBackgroundJobs(ProductTranslationService translations, InspectionService inspections, @Lazy ProductBackgroundJobs self) {
        this.translations = translations;
        this.inspections = inspections;
        this.self = self;   // @Async 는 프록시를 거쳐야 다른 스레드에서 돈다 (this.run 은 같은 스레드)
    }

    /** 바뀐 칸만 다시 번역 */
    public void translate(String productId) {
        AfterCommit.run(() -> self.runTranslate(productId));
    }

    /** 규칙 + AI 검수를 돌려 기록을 남긴다 */
    public void inspect(String productId) {
        AfterCommit.run(() -> self.runInspect(productId));
    }

    @Async(AsyncConfig.BACKGROUND)
    public void runTranslate(String productId) {
        try {
            translations.sync(productId);
        } catch (RuntimeException e) {
            log.error("[translation] 백그라운드 번역 실패 {}", productId, e);
        }
    }

    @Async(AsyncConfig.BACKGROUND)
    public void runInspect(String productId) {
        try {
            inspections.inspectProduct(productId);
        } catch (RuntimeException e) {
            log.error("[inspect] 백그라운드 검수 실패 {}", productId, e);
            inspections.recordFailure(productId);
        }
    }
}
