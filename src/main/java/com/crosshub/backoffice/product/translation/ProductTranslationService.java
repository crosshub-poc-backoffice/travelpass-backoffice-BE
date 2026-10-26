package com.crosshub.backoffice.product.translation;

import com.crosshub.backoffice.account.AuditLog;
import com.crosshub.backoffice.account.AuditLogRepository;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductException;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.translation.ProductTexts.Outdated;
import com.crosshub.backoffice.product.translation.TranslateService.TranslateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 번역. FE lib/server/products/translations.ts 의 쓰기 부분을 옮긴 것. 원문은 Product 그대로, 언어별 번역은 ProductTranslation.
 * - 상품을 저장하면 모든 언어에서 바뀐 칸만 자동으로 다시 번역한다 (sync — ProductBackgroundJobs 가 커밋 뒤에 돌린다)
 * - 다시 번역한 언어는 검수 전으로 돌아간다 (새 기계 번역을 다시 확인하라는 뜻)
 * - 모든 언어에 빈 칸이 없어야 노출(live)할 수 있다 (complete). 검수 완료는 필수가 아니다
 * 조회(번역 화면·목록 번역 상태)는 아직 FE 가 DB 에서 직접 읽는다 — 조회 이전 단계에서 옮긴다.
 */
@Service
public class ProductTranslationService {

    private static final Logger log = LoggerFactory.getLogger(ProductTranslationService.class);
    /** 사람이 고친 번역 한 칸 상한 (FE validate 의 가장 긴 칸 description 4000 과 맞춤) */
    static final int TEXT_MAX = 4000;

    private final ProductRepository products;
    private final ProductTranslationRepository translations;
    private final TranslateService translate;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper om;

    public ProductTranslationService(ProductRepository products, ProductTranslationRepository translations,
                                     TranslateService translate, AuditLogRepository auditLogs, ObjectMapper om) {
        this.products = products;
        this.translations = translations;
        this.translate = translate;
        this.auditLogs = auditLogs;
        this.om = om;
    }

    private Map<String, String> sourceMap(Product p) {
        return ProductTexts.sourceMap(p.getName(), p.getDescription(), p.getPriceTable(), p.getBlocks(), om);
    }

    /** 원문 언어. 비어 있으면 상품명·소개로 감지해서 저장한다 */
    private String ensureSourceLocale(Product p) {
        if (p.getSourceLocale() != null && !p.getSourceLocale().isBlank()) return p.getSourceLocale();
        String detected = Locales.normalize(translate.detectLanguage(p.getName() + "\n" + p.getDescription()));
        if (detected != null) {
            p.setSourceLocale(detected);
            products.save(p);
        }
        return detected;
    }

    /** 한 언어의 keys 칸을 번역해 저장. 기본 제목은 표에서, 나머지는 번역 API 로. 원문에서 사라진 칸은 지운다. 번역한 언어는 검수 전으로 */
    private void translateKeys(Product p, String source, String locale, List<String> keys) {
        Map<String, String> current = sourceMap(p);
        Optional<ProductTranslation> row = translations.findByProductIdAndLocale(p.getId(), locale);
        Map<String, String> texts = new LinkedHashMap<>(row.map(r -> ProductTexts.asMap(r.getTexts(), om)).orElse(Map.of()));
        Map<String, String> sources = new LinkedHashMap<>(row.map(r -> ProductTexts.asMap(r.getSources(), om)).orElse(Map.of()));

        List<String> apiKeys = keys.stream().filter(k -> DefaultTitles.translate(current.get(k), locale) == null).toList();
        List<String> translated = translate.translateMany(apiKeys.stream().map(current::get).toList(), locale, source);
        for (int i = 0; i < apiKeys.size(); i++) texts.put(apiKeys.get(i), translated.get(i));
        for (String k : keys) {
            String table = DefaultTitles.translate(current.get(k), locale);
            if (table != null) texts.put(k, table);
            sources.put(k, current.get(k));
        }
        texts.keySet().removeIf(k -> !current.containsKey(k));
        sources.keySet().removeIf(k -> !current.containsKey(k));

        upsert(p.getId(), locale, row, texts, sources, false);
    }

    private void upsert(String productId, String locale, Optional<ProductTranslation> row,
                        Map<String, String> texts, Map<String, String> sources, boolean reviewed) {
        String t = ProductTexts.toJson(texts, om);
        String s = ProductTexts.toJson(sources, om);
        if (row.isPresent()) {
            row.get().update(t, s, reviewed);
            translations.save(row.get());
        } else {
            translations.save(ProductTranslation.of(productId, locale, t, s, reviewed));
        }
    }

    /**
     * 저장 후 자동 번역. 모든 언어에서 비었거나 원문이 바뀐 칸만 번역한다 (검수 완료 언어도 — 다시 번역하면 검수 전으로 돌아간다).
     * 임시저장은 번역하지 않는다. 실패해도 저장은 이미 끝났으니 로그만.
     */
    @Transactional
    public void sync(String productId) {
        try {
            Product p = products.findById(productId).orElse(null);
            if (p == null || "draft".equals(p.getStatus())) return;
            String source = ensureSourceLocale(p);
            if (source != null) translations.deleteByProductIdAndLocale(productId, source);

            Map<String, String> current = sourceMap(p);
            List<ProductTranslation> rows = translations.findByProductId(productId);
            for (String locale : Locales.targets(source)) {
                Optional<ProductTranslation> row = rows.stream().filter(r -> r.getLocale().equals(locale)).findFirst();
                Map<String, String> texts = row.map(r -> ProductTexts.asMap(r.getTexts(), om)).orElse(Map.of());
                Map<String, String> sources = row.map(r -> ProductTexts.asMap(r.getSources(), om)).orElse(Map.of());
                Outdated o = ProductTexts.outdated(current, sources, texts);
                boolean removed = texts.keySet().stream().anyMatch(k -> !current.containsKey(k));
                if (o.missing().isEmpty() && o.stale().isEmpty() && !removed && row.isPresent()) continue;
                List<String> keys = new ArrayList<>(o.missing());
                keys.addAll(o.stale());
                translateKeys(p, source, locale, keys);
            }
        } catch (RuntimeException e) {
            log.error("상품 자동 번역 실패 ({})", productId, e);
        }
    }

    /** 노출(live) 가능한지: 원문 언어를 뺀 모든 언어에 빈 칸 없이 번역이 있어야 한다. 검수 완료는 필요 없다 */
    @Transactional(readOnly = true)
    public boolean complete(Product p) {
        if (p.getSourceLocale() == null || p.getSourceLocale().isBlank()) return false;
        Map<String, String> current = sourceMap(p);
        List<ProductTranslation> rows = translations.findByProductId(p.getId());
        for (String locale : Locales.targets(p.getSourceLocale())) {
            Optional<ProductTranslation> row = rows.stream().filter(r -> r.getLocale().equals(locale)).findFirst();
            if (row.isEmpty()) return false;
            Outdated o = ProductTexts.outdated(current, ProductTexts.asMap(row.get().getSources(), om), ProductTexts.asMap(row.get().getTexts(), om));
            if (!o.missing().isEmpty()) return false;
        }
        return true;
    }

    /** 아직 번역한 적 없는 상품의 원문 언어를 정한다 (번역 화면이 열릴 때). 감지 실패면 null — 화면에서 고르게 */
    @Transactional
    public String detectSource(Caller caller, String productId) {
        Product p = scoped(caller, productId);
        try {
            return ensureSourceLocale(p);
        } catch (TranslateException e) {
            log.warn("원문 언어 감지 실패 ({})", productId, e);
            return null;
        }
    }

    /** 그 언어의 모든 칸을 다시 번역 (번역 화면의 "다시 번역" 버튼). 사람이 고친 번역도 덮어쓴다. 다시 번역한 언어는 검수 전으로 */
    @Transactional
    public void retranslate(Caller caller, String productId, String locale) {
        Product p = scoped(caller, productId);
        if (!Locales.isLocale(locale)) throw notFound();
        try {
            String source = ensureSourceLocale(p);
            if (locale.equals(source)) throw notFound();
            translateKeys(p, source, locale, new ArrayList<>(sourceMap(p).keySet()));
        } catch (TranslateException e) {
            log.warn("상품 다시 번역 실패 ({}, {})", productId, locale, e);
            throw new ProductException(HttpStatus.BAD_GATEWAY, "translateFailed");
        }
        audit(caller, p, "{\"locale\":[null,\"" + locale + "\"]}");
    }

    /** 사람이 고친 번역 저장. edits = 고친 칸만 (키 → 글). review 면 검수 완료로 — 빈 칸이 있으면 안 되고, 원문 변경 칸도 확인한 걸로 본다 */
    @Transactional
    public void save(Caller caller, String productId, String locale, Map<String, String> edits, boolean review) {
        Product p = scoped(caller, productId);
        if (!Locales.isLocale(locale) || locale.equals(p.getSourceLocale())) throw notFound();
        Map<String, String> current = sourceMap(p);
        Optional<ProductTranslation> row = translations.findByProductIdAndLocale(productId, locale);
        Map<String, String> texts = new LinkedHashMap<>(row.map(r -> ProductTexts.asMap(r.getTexts(), om)).orElse(Map.of()));
        Map<String, String> sources = new LinkedHashMap<>(row.map(r -> ProductTexts.asMap(r.getSources(), om)).orElse(Map.of()));

        if (edits != null) {
            for (Map.Entry<String, String> e : edits.entrySet()) {
                if (!current.containsKey(e.getKey()) || e.getValue() == null) continue;
                if (e.getValue().length() > TEXT_MAX) throw new ProductException(HttpStatus.BAD_REQUEST, "tooLong");
                texts.put(e.getKey(), e.getValue().trim());
                sources.put(e.getKey(), current.get(e.getKey()));
            }
        }
        if (review) {
            boolean incomplete = current.keySet().stream().anyMatch(k -> texts.get(k) == null || texts.get(k).trim().isEmpty());
            if (incomplete) throw new ProductException(HttpStatus.CONFLICT, "translationIncomplete");
            sources.putAll(current);
        }
        boolean wasReviewed = row.map(ProductTranslation::isReviewed).orElse(false);
        boolean reviewed = review || wasReviewed;
        upsert(productId, locale, row, texts, sources, reviewed);
        String detail = wasReviewed != reviewed
                ? "{\"locale\":[null,\"" + locale + "\"],\"reviewed\":[" + wasReviewed + "," + reviewed + "]}"
                : "{\"locale\":[null,\"" + locale + "\"]}";
        audit(caller, p, detail);
    }

    /** 원문 언어를 바로잡는다. 그 언어의 번역은 지우고, 원래 원문 언어로의 번역은 다음 자동 번역(호출부가 건다)이 채운다 */
    @Transactional
    public void changeSourceLocale(Caller caller, String productId, String locale) {
        Product p = scoped(caller, productId);
        if (!Locales.isLocale(locale)) throw notFound();
        String before = p.getSourceLocale();
        p.setSourceLocale(locale);
        p.setUpdatedAt(OffsetDateTime.now());
        products.save(p);
        translations.deleteByProductIdAndLocale(productId, locale);
        audit(caller, p, "{\"sourceLocale\":[" + (before == null ? "null" : "\"" + before + "\"") + ",\"" + locale + "\"]}");
    }

    /** 범위 안의 상품인지 확인만 (백그라운드 번역을 걸기 전) */
    @Transactional(readOnly = true)
    public void requireScoped(Caller caller, String productId) {
        scoped(caller, productId);
    }

    private Product scoped(Caller caller, String productId) {
        Product p = products.findById(productId).orElseThrow(ProductTranslationService::notFound);
        if (!caller.canAccessPartner(p.getPartnerId())) throw notFound();
        return p;
    }

    private void audit(Caller caller, Product p, String detail) {
        auditLogs.save(AuditLog.of(caller.userId(), "product_translation", p.getPartnerId(), p.getId(), p.getName(), detail));
    }

    private static ProductException notFound() {
        return new ProductException(HttpStatus.NOT_FOUND, "notFound");
    }
}
