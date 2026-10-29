package com.crosshub.backoffice.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** FE lib/products/schedule.ts resolve() 포트 — 기본 일정·요일·예외·회차별 값 */
class SaleScheduleTest {

    private final ObjectMapper om = new ObjectMapper();

    // 2026-11-02(월) ~ 2026-11-30, 월~금, 하루 단위, 정원 10. 11-10 은 예외로 닫힘, 11-11 은 예외로 정원 3
    private static final String SCHEDULE = """
        {"base":{"start":"2026-11-02","end":"2026-11-30","weekdays":[1,2,3,4,5],"times":[],"slotPeople":{},"minPeople":1,"maxPeople":10},
         "exceptions":[
           {"id":"a","start":"2026-11-10","end":"2026-11-10","open":false,"slots":{},"minPeople":null,"maxPeople":null},
           {"id":"b","start":"2026-11-11","end":"2026-11-11","open":true,"slots":{},"minPeople":1,"maxPeople":3}
         ]}
        """;

    @Test
    void 기본_일정_요일_안이면_열림_정원은_base() {
        SaleSchedule.Day d = SaleSchedule.resolve(SCHEDULE, LocalDate.of(2026, 11, 3), om); // 화
        assertThat(d.open()).isTrue();
        assertThat(d.maxPeople()).isEqualTo(10);
    }

    @Test
    void 주말은_닫힘_기간_밖도_닫힘() {
        assertThat(SaleSchedule.resolve(SCHEDULE, LocalDate.of(2026, 11, 1), om).open()).isFalse();  // 일 (weekday 0)
        assertThat(SaleSchedule.resolve(SCHEDULE, LocalDate.of(2026, 11, 7), om).open()).isFalse();  // 토
        assertThat(SaleSchedule.resolve(SCHEDULE, LocalDate.of(2026, 12, 1), om).open()).isFalse();  // 기간 밖(화)
    }

    @Test
    void 예외가_기본보다_우선() {
        assertThat(SaleSchedule.resolve(SCHEDULE, LocalDate.of(2026, 11, 10), om).open()).isFalse(); // 화인데 닫힘
        SaleSchedule.Day d = SaleSchedule.resolve(SCHEDULE, LocalDate.of(2026, 11, 11), om);
        assertThat(d.open()).isTrue();
        assertThat(d.maxPeople()).isEqualTo(3);
    }

    @Test
    void 회차별_값이_있으면_그_회차_기준() {
        String s = """
            {"base":{"start":"2026-11-02","end":"2026-11-30","weekdays":[1,2,3,4,5],"times":["09:00","14:00"],
                     "slotPeople":{"14:00":{"minPeople":1,"maxPeople":4}},"minPeople":1,"maxPeople":8},
             "exceptions":[{"id":"x","start":"2026-11-04","end":"2026-11-04","open":true,"slots":{"09:00":{"open":false,"minPeople":null,"maxPeople":null}}}]}
            """;
        SaleSchedule.Day d = SaleSchedule.resolve(s, LocalDate.of(2026, 11, 4), om); // 수: 09:00 닫힘 → 첫 열린 회차 14:00 (정원 4)
        assertThat(d.open()).isTrue();
        assertThat(d.maxPeople()).isEqualTo(4);
    }

    @Test
    void 설정_안_된_schedule은_null_무제한() {
        assertThat(SaleSchedule.resolve("{}", LocalDate.of(2026, 11, 3), om)).isNull();
        assertThat(SaleSchedule.resolve(null, LocalDate.of(2026, 11, 3), om)).isNull();
    }
}
