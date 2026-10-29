package com.crosshub.backoffice.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crosshub.backoffice.partner.dto.PartnerUpsertRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 업체 쓰기의 순수 로직: 입력 검사(FE actions.ts parse와 같은 규칙), 이력 diff */
class PartnerCommandServiceTest {

    private static PartnerUpsertRequest req(String name, String currency, String email) {
        return new PartnerUpsertRequest(name, null, currency, null, null, email, null,
                null, null, null, null, null, null, null);
    }

    private static String code(Runnable r) {
        try {
            r.run();
            return null;
        } catch (PartnerException e) {
            return e.code();
        }
    }

    @Test
    void validate_trims_and_defaults_currency() {
        PartnerUpsertRequest n = PartnerCommandService.validate(req("  가나다  ", " ", "  "));
        assertThat(n.name()).isEqualTo("가나다");
        assertThat(n.currency()).isEqualTo("JPY");
        assertThat(n.email()).isNull();
    }

    @Test
    void validate_rejects_bad_input_with_fe_error_keys() {
        assertThat(code(() -> PartnerCommandService.validate(req(" ", "JPY", null)))).isEqualTo("required");
        assertThat(code(() -> PartnerCommandService.validate(req("a".repeat(51), "JPY", null)))).isEqualTo("nameLength");
        assertThat(code(() -> PartnerCommandService.validate(req("a", "jpy", null)))).isEqualTo("currency");
        assertThat(code(() -> PartnerCommandService.validate(req("a", "JPY", "no-at")))).isEqualTo("emailFormat");
        assertThat(code(() -> PartnerCommandService.validate(req("a", "KRW", "a@b.co")))).isNull();
    }

    @Test
    void diff_keeps_only_changed_fields() {
        Map<String, String> before = new LinkedHashMap<>();
        before.put("name", "A");
        before.put("address", "후쿠오카");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("name", "A");
        after.put("address", "벳푸");

        Map<String, Object[]> d = PartnerCommandService.diff(before, after);
        assertThat(d).containsOnlyKeys("address");
        assertThat(d.get("address")).containsExactly("후쿠오카", "벳푸");
    }

    @Test
    void exception_carries_code() {
        assertThatThrownBy(() -> PartnerCommandService.validate(req(null, null, null)))
                .isInstanceOf(PartnerException.class).hasMessage("required");
    }
}
