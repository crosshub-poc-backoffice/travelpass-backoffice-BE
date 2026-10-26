package com.crosshub.backoffice.product.inspect;

import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.inspect.InspectTarget.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 검수 파이프라인 + 기록. FE lib/server/inspect/{pipeline,service}.ts 포트.
 * 규칙 먼저(산수라 즉시), AI 는 그 뒤에. 검사기 하나가 터져도 나머지는 살린다.
 * 결과(verdict: pass | warn | review)는 ProductInspection 에 남긴다. 화면은 아직 안 읽는다 — 기록용.
 */
@Service
public class InspectionService {

    private static final Logger log = LoggerFactory.getLogger(InspectionService.class);

    public record Result(String verdict, List<Finding> findings, boolean aiRan) {}

    private final ProductRepository products;
    private final ProductInspectionRepository inspections;
    private final List<Checker> checkers;
    private final ObjectMapper om;

    public InspectionService(ProductRepository products, ProductInspectionRepository inspections, AiChecker ai, ObjectMapper om) {
        this.products = products;
        this.inspections = inspections;
        this.om = om;
        List<Checker> all = new ArrayList<>(RuleCheckers.all(om));
        all.add(ai);
        this.checkers = List.copyOf(all);
    }

    /** 대상 하나를 검수한다 (DB 없이). ai=false 면 규칙만 */
    public Result run(InspectTarget target, boolean ai) {
        List<Checker> picked = checkers.stream().filter(c -> (ai || !c.isAi()) && c.appliesTo(target)).toList();
        List<Finding> findings = new ArrayList<>();
        for (Checker c : picked) {
            try {
                findings.addAll(c.run(target));
            } catch (RuntimeException e) {
                log.error("[inspect] 검사기 실패: {}", c.code(), e);
            }
        }
        findings.sort(Comparator.comparingInt(f -> Finding.rank(f.severity())));
        boolean aiRan = picked.stream().anyMatch(Checker::isAi);
        return new Result(verdictOf(findings), findings, aiRan);
    }

    static String verdictOf(List<Finding> findings) {
        if (findings.stream().anyMatch(f -> "high".equals(f.severity()))) return "review";
        return findings.isEmpty() ? "pass" : "warn";
    }

    /** 상품 한 건 검수(최초 심사용). 결과를 ProductInspection 에 남기고 돌려준다. 상품이 없으면 null */
    @Transactional
    public Result inspectProduct(String productId) {
        Product p = products.findById(productId).orElse(null);
        if (p == null) return null;
        Result r = run(new InspectTarget(null, Snapshot.of(p, om)), true);
        inspections.save(ProductInspection.of(productId, null, "done", r.verdict(), r.aiRan(), toJson(r.findings())));
        return r;
    }

    /** 검수 자체가 터졌을 때 실패 기록. 이것까지 실패하면 로그만 */
    @Transactional
    public void recordFailure(String productId) {
        try {
            inspections.save(ProductInspection.of(productId, null, "failed", "pass", false, "[]"));
        } catch (RuntimeException e) {
            log.error("[inspect] 실패 기록도 실패 {}", productId, e);
        }
    }

    private String toJson(List<Finding> findings) {
        try {
            return om.writeValueAsString(findings);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
