package com.crosshub.backoffice.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosshub.backoffice.product.PriceFrom;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 판매기간·대표가 계산 — FE saleRange()/priceFrom() 과 같은 답 */
class SaleRangeTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 기본일정_요일과_예외를_합쳐_첫날_마지막날() {
        // 2026-06-01 은 월요일. 평일(1~5)만 팔고, 6/13(토)에 특별 판매 예외
        String s = """
            {"base":{"start":"2026-06-01","end":"2026-06-10","weekdays":[1,2,3,4,5],"times":[],"slotPeople":{},"minPeople":null,"maxPeople":null},
             "exceptions":[{"id":"e1","start":"2026-06-13","end":"2026-06-13","open":true,"minPeople":null,"maxPeople":null,"slots":{}}]}
            """;
        SaleRange.Range r = SaleRange.of(s, om);
        assertThat(r.start()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(r.end()).isEqualTo(LocalDate.of(2026, 6, 13));
    }

    @Test
    void 파는_날이_없으면_null() {
        assertThat(SaleRange.of("{}", om)).isNull();
        assertThat(SaleRange.of("{\"base\":{\"start\":\"2026-06-06\",\"end\":\"2026-06-07\",\"weekdays\":[1],\"times\":[]},\"exceptions\":[]}", om)).isNull();
    }

    @Test
    void 대표가는_첫_기준의_최저가_숙박은_객실_기본요금() {
        assertThat(PriceFrom.of("{\"categories\":[{\"id\":\"a\"},{\"id\":\"c\"}],\"plans\":[{\"prices\":{\"a\":3000,\"c\":100}},{\"prices\":{\"a\":2000}}],\"roomTypes\":[]}", om)).isEqualTo(2000);
        assertThat(PriceFrom.of("{\"categories\":[],\"plans\":[],\"roomTypes\":[{\"id\":\"r\"}],\"roomPricing\":{\"base\":{\"r\":{\"price\":9000},\"x\":{\"price\":null}}}}", om)).isEqualTo(9000);
        assertThat(PriceFrom.of("{\"categories\":[],\"plans\":[]}", om)).isNull();
    }
}
