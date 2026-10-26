package com.crosshub.backoffice.product.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosshub.backoffice.product.inspect.InspectTarget.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 규칙 검수 — FE lib/server/inspect/rules/* 와 같은 지적을 내는지 */
class RuleCheckersTest {

    ObjectMapper om = new ObjectMapper();

    Snapshot snap(String partnerId, List<String> images, int deadlineDays, String deadlineTime, String schedule, String cancelRules, String priceTable) {
        return Snapshot.of(partnerId, "tour", "상품", images, "소개", deadlineDays, deadlineTime, "instant", schedule, cancelRules, priceTable, "[]", om);
    }

    static final String SCHEDULE = """
        {"base":{"start":"2026-06-01","end":"2026-06-07","weekdays":[0,1,2,3,4,5,6],"times":[],"slotPeople":{},"minPeople":null,"maxPeople":null},"exceptions":[]}
        """;

    List<Finding> run(InspectTarget t) {
        return new InspectionServiceLite(om).run(t);
    }

    /** AI 없이 규칙만 돌리는 작은 러너 */
    record InspectionServiceLite(ObjectMapper om) {
        List<Finding> run(InspectTarget t) {
            return RuleCheckers.all(om).stream().filter(c -> c.appliesTo(t)).flatMap(c -> c.run(t).stream()).toList();
        }
    }

    @Test
    void 아동이_성인보다_비싸거나_0엔이면_지적() {
        String pt = """
            {"categories":[{"id":"a","name":"성인"},{"id":"c","name":"아동"}],"plans":[{"id":"p","name":"기본","prices":{"a":1000,"c":2000}},{"id":"q","name":"무료","prices":{"a":0,"c":0}}],
             "priceImages":[],"priceText":"","title":"","roomTypes":[],"roomPricing":{"base":{},"exceptions":[]}}
            """;
        List<Finding> f = run(new InspectTarget(null, snap("pA", List.of(), 1, "17:00", SCHEDULE, "[]", pt)));
        assertThat(f).extracting(Finding::code).contains("CHILD_PRICE_OVER_ADULT", "PRICE_ZERO");
        assertThat(f.stream().filter(x -> x.code().equals("CHILD_PRICE_OVER_ADULT")).findFirst().orElseThrow().evidence())
                .containsEntry("adult", 1000).containsEntry("child", 2000);
    }

    @Test
    void 자릿수가_튀는_단가는_outlier() {
        String pt = """
            {"categories":[{"id":"a","name":"성인"}],"plans":[{"id":"p1","name":"A","prices":{"a":1000}},{"id":"p2","name":"B","prices":{"a":1200}},{"id":"p3","name":"C","prices":{"a":120000}}],
             "priceImages":[],"priceText":"","title":"","roomTypes":[],"roomPricing":{"base":{},"exceptions":[]}}
            """;
        List<Finding> f = run(new InspectTarget(null, snap("pA", List.of(), 1, "17:00", SCHEDULE, "[]", pt)));
        assertThat(f).extracting(Finding::code).contains("PRICE_OUTLIER");
        assertThat(f).extracting(Finding::field).contains("priceTable.plans.p3.prices.a");
    }

    @Test
    void 취소규정_범위_중복_역전_마감형식_판매일없음() {
        List<Finding> f = run(new InspectTarget(null, snap("pA", List.of(), 400, "25:00", "{}",
                "[{\"daysBefore\":7,\"percent\":50},{\"daysBefore\":7,\"percent\":120},{\"daysBefore\":3,\"percent\":10}]",
                "{\"categories\":[],\"plans\":[],\"roomTypes\":[],\"roomPricing\":{\"base\":{}}}")));
        assertThat(f).extracting(Finding::code)
                .contains("CANCEL_RULE_RANGE", "CANCEL_RULE_DUPLICATE", "CANCEL_RULE_NOT_INCREASING", "DEADLINE_INVALID", "SALE_RANGE_EMPTY");
        assertThat(f.stream().filter(x -> x.code().equals("DEADLINE_INVALID")).count()).isEqualTo(2);
        assertThat(InspectionService.verdictOf(f)).isEqualTo("review");
        assertThat(InspectionService.verdictOf(List.of())).isEqualTo("pass");
    }

    @Test
    void 변경요청은_대표가_급변_취소완화_사진삭제_업체변경을_짚는다() {
        String before = "{\"categories\":[{\"id\":\"a\",\"name\":\"성인\"}],\"plans\":[{\"id\":\"p\",\"name\":\"\",\"prices\":{\"a\":10000}}],\"roomTypes\":[],\"roomPricing\":{\"base\":{}}}";
        String after = "{\"categories\":[{\"id\":\"a\",\"name\":\"성인\"}],\"plans\":[{\"id\":\"p\",\"name\":\"\",\"prices\":{\"a\":20000}}],\"roomTypes\":[],\"roomPricing\":{\"base\":{}}}";
        Snapshot cur = snap("pA", List.of("/seed-images/a.jpg"), 1, "17:00", SCHEDULE, "[{\"daysBefore\":7,\"percent\":50},{\"daysBefore\":3,\"percent\":80}]", before);
        Snapshot next = snap("pB", List.of(), 1, "17:00", SCHEDULE, "[{\"daysBefore\":7,\"percent\":30}]", after);
        List<Finding> f = run(new InspectTarget(cur, next));
        assertThat(f).extracting(Finding::code).contains("PRICE_CHANGED_LARGE", "CANCEL_RULE_RELAXED", "IMAGES_ALL_REMOVED", "PARTNER_CHANGED");
        assertThat(f.stream().filter(x -> x.code().equals("CANCEL_RULE_RELAXED")).count()).isEqualTo(2); // 7일 완화 + 3일 삭제
        assertThat(f.stream().filter(x -> x.code().equals("PRICE_CHANGED_LARGE")).findFirst().orElseThrow().evidence()).containsEntry("rate", 100.0);
    }

    @Test
    void 깨끗한_상품은_지적_없음() {
        String pt = """
            {"categories":[{"id":"a","name":"성인"},{"id":"c","name":"아동"}],"plans":[{"id":"p","name":"기본","prices":{"a":10000,"c":5000}}],
             "priceImages":[],"priceText":"","title":"","roomTypes":[],"roomPricing":{"base":{},"exceptions":[]}}
            """;
        List<Finding> f = run(new InspectTarget(null, snap("pA", List.of(), 1, "17:00", SCHEDULE, "[{\"daysBefore\":7,\"percent\":30},{\"daysBefore\":3,\"percent\":50}]", pt)));
        assertThat(f).isEmpty();
    }
}
