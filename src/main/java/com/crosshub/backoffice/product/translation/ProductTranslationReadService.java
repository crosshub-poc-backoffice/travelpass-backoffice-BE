package com.crosshub.backoffice.product.translation;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductException;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.translation.ProductTexts.Entry;
import com.crosshub.backoffice.product.translation.ProductTexts.Outdated;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 번역 조회. FE lib/server/products/translations.ts 의 읽기 부분(번역 화면·목록 번역 상태·보는 사람 언어로 바꾸기)을 옮긴 것.
 */
@Service
public class ProductTranslationReadService {

    /** 번역 화면 한 언어 */
    public record LocaleView(String locale, String state, Map<String, String> texts, List<String> missing, List<String> stale,
                             Map<String, String> previousSources, OffsetDateTime updatedAt) {}

    /** 번역 화면 전체 (FE ProductTranslations) */
    public record TranslationsView(String id, String name, String sourceLocale, List<Entry> entries, List<String> blockTitles, List<LocaleView> locales) {}

    /** 한 상품·한 언어의 번역 원본 (키 → 번역문 / 키 → 번역할 때의 원문). FE 가 대시보드·문의 화면의 상품명을 보는 사람 언어로 바꾸는 데 쓴다 */
    public record Row(String productId, Map<String, String> texts, Map<String, String> sources) {}

    private final ProductRepository products;
    private final ProductTranslationRepository translations;
    private final ProductTranslationService writes;
    private final ObjectMapper om;

    public ProductTranslationReadService(ProductRepository products, ProductTranslationRepository translations,
                                         ProductTranslationService writes, ObjectMapper om) {
        this.products = products;
        this.translations = translations;
        this.writes = writes;
        this.om = om;
    }

    /** 번역 화면. 아직 번역한 적 없는 상품(시드·기존 상품)은 여기서 원문 언어부터 정한다 — 감지 실패면 null 로 두고 화면에서 고르게 */
    @Transactional
    public TranslationsView view(Caller caller, String productId) {
        Product p = products.findById(productId).orElseThrow(() -> new ProductException(HttpStatus.NOT_FOUND, "notFound"));
        if (!caller.canAccessPartner(p.getPartnerId())) throw new ProductException(HttpStatus.NOT_FOUND, "notFound");
        String sourceLocale = writes.detectSource(caller, productId);
        Map<String, String> current = ProductTexts.sourceMap(p.getName(), p.getDescription(), p.getPriceTable(), p.getBlocks(), om);
        List<ProductTranslation> rows = translations.findByProductId(productId);
        List<LocaleView> locales = new ArrayList<>();
        for (String locale : Locales.targets(sourceLocale)) {
            Optional<ProductTranslation> row = rows.stream().filter(r -> r.getLocale().equals(locale)).findFirst();
            Map<String, String> texts = row.map(r -> ProductTexts.asMap(r.getTexts(), om)).orElse(Map.of());
            Map<String, String> sources = row.map(r -> ProductTexts.asMap(r.getSources(), om)).orElse(Map.of());
            Outdated o = ProductTexts.outdated(current, sources, texts);
            Map<String, String> previous = new LinkedHashMap<>();
            for (String k : o.stale()) previous.put(k, sources.getOrDefault(k, ""));
            locales.add(new LocaleView(locale, row.isEmpty() ? "none" : row.get().isReviewed() ? "reviewed" : "auto",
                    texts, o.missing(), o.stale(), previous, row.map(ProductTranslation::getUpdatedAt).orElse(null)));
        }
        return new TranslationsView(p.getId(), p.getName(), sourceLocale,
                ProductTexts.entries(p.getName(), p.getDescription(), p.getPriceTable(), p.getBlocks(), om),
                ProductTexts.blockTitles(p.getBlocks(), om), locales);
    }

    /**
     * 목록에 보여줄 상품 하나의 번역 상태 (언어 5개를 한 칸으로). 임시저장은 null.
     * none = 빈 칸이 있는 언어가 있음(번역 실패·중간에 멈춤 — 이 상태로는 노출 불가), auto = 자동 번역(검수 안 한 언어가 있음), reviewed = 전부 검수 완료
     */
    public String summary(Product p, List<ProductTranslation> rows) {
        if ("draft".equals(p.getStatus())) return null;
        Map<String, String> current = ProductTexts.sourceMap(p.getName(), p.getDescription(), p.getPriceTable(), p.getBlocks(), om);
        List<String> targets = Locales.targets(p.getSourceLocale());
        List<ProductTranslation> present = new ArrayList<>();
        for (String locale : targets) rows.stream().filter(r -> r.getLocale().equals(locale)).findFirst().ifPresent(present::add);
        boolean complete = p.getSourceLocale() != null && present.size() == targets.size()
                && present.stream().allMatch(r -> ProductTexts.outdated(current, ProductTexts.asMap(r.getSources(), om), ProductTexts.asMap(r.getTexts(), om)).missing().isEmpty());
        if (!complete) return "none";
        return present.stream().allMatch(ProductTranslation::isReviewed) ? "reviewed" : "auto";
    }

    /** 상품명을 보는 사람 언어로: productId → 번역 상품명 (번역이 없거나 빈 칸이면 키가 없다) */
    @Transactional(readOnly = true)
    public Map<String, String> names(Collection<String> productIds, String locale) {
        Map<String, String> out = new LinkedHashMap<>();
        if (productIds.isEmpty() || locale == null || !Locales.isLocale(locale)) return out;
        for (ProductTranslation t : translations.findByProductIdInAndLocale(productIds, locale)) {
            String n = ProductTexts.asMap(t.getTexts(), om).get("name");
            if (n != null && !n.trim().isEmpty()) out.put(t.getProductId(), n.trim());
        }
        return out;
    }

    /** 상품들의 한 언어 번역 원본. 범위 밖 상품은 뺀다 */
    @Transactional(readOnly = true)
    public List<Row> rows(Caller caller, Collection<String> productIds, String locale) {
        if (productIds.isEmpty() || !Locales.isLocale(locale)) return List.of();
        Map<String, Product> byId = new LinkedHashMap<>();
        for (Product p : products.findAllById(productIds)) if (caller.canAccessPartner(p.getPartnerId())) byId.put(p.getId(), p);
        List<Row> out = new ArrayList<>();
        for (ProductTranslation t : translations.findByProductIdInAndLocale(byId.keySet(), locale)) {
            out.add(new Row(t.getProductId(), ProductTexts.asMap(t.getTexts(), om), ProductTexts.asMap(t.getSources(), om)));
        }
        return out;
    }
}
