package com.crosshub.backoffice.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crosshub.backoffice.partner.dto.PartnerUpsertRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 업체 쓰기의 순수 로직: 입력 검사(FE actions.ts parse와 같은 규칙), 이력 diff, 계좌 마스킹 */
class PartnerCommandServiceTest {

    private static PartnerUpsertRequest req(String name, Integer rate, String currency, String email) {
        return new PartnerUpsertRequest(name, null, rate, currency, null, null, email, null, null, null,
                null, null, null, null, null, null, null, null, null);
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
        PartnerUpsertRequest n = PartnerCommandService.validate(req("  가나다  ", 15, " ", "  "));
        assertThat(n.name()).isEqualTo("가나다");
        assertThat(n.currency()).isEqualTo("JPY");
        assertThat(n.email()).isNull();
    }

    @Test
    void validate_rejects_bad_input_with_fe_error_keys() {
        assertThat(code(() -> PartnerCommandService.validate(req(" ", null, "JPY", null)))).isEqualTo("required");
        assertThat(code(() -> PartnerCommandService.validate(req("a".repeat(51), null, "JPY", null)))).isEqualTo("nameLength");
        assertThat(code(() -> PartnerCommandService.validate(req("a", 101, "JPY", null)))).isEqualTo("commissionRate");
        assertThat(code(() -> PartnerCommandService.validate(req("a", -1, "JPY", null)))).isEqualTo("commissionRate");
        assertThat(code(() -> PartnerCommandService.validate(req("a", null, "jpy", null)))).isEqualTo("currency");
        assertThat(code(() -> PartnerCommandService.validate(req("a", null, "JPY", "no-at")))).isEqualTo("emailFormat");
        assertThat(code(() -> PartnerCommandService.validate(req("a", 100, "KRW", "a@b.co")))).isNull();
    }

    @Test
    void diff_keeps_only_changed_fields_and_masks_account() {
        Map<String, String> before = new LinkedHashMap<>();
        before.put("bankName", "A");
        before.put("bankAccountNumber", "1234567890");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("bankName", "A");
        after.put("bankAccountNumber", "1111222233");

        Map<String, Object[]> d = PartnerCommandService.diff(before, after, "bankAccountNumber"::equals);
        assertThat(d).containsOnlyKeys("bankAccountNumber");
        assertThat(d.get("bankAccountNumber")).containsExactly("****7890", "****2233");
    }

    @Test
    void maskAccount_handles_null_and_short() {
        assertThat(PartnerCommandService.maskAccount(null)).isNull();
        assertThat(PartnerCommandService.maskAccount("12")).isEqualTo("****12");
    }

    @Test
    void exception_carries_code() {
        assertThatThrownBy(() -> PartnerCommandService.validate(req(null, null, null, null)))
                .isInstanceOf(PartnerException.class).hasMessage("required");
    }
}
