package com.crosshub.backoffice.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.account.keycloak.KeycloakAdminClient;
import com.crosshub.backoffice.notification.NotificationRepository;
import com.crosshub.backoffice.partner.AffiliateRepository;
import com.crosshub.backoffice.partner.PartnerRepository;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 계정 발급 고아 정리·롤백, 활성 토글 되돌리기 */
class AccountRollbackTest {

    static final Caller ADMIN = new Caller("u-admin", "test-crosshub", "crosshub", null, null, List.of());

    UserAccountRepository users = mock(UserAccountRepository.class);
    KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    AccountCommandService service = new AccountCommandService(users, mock(AffiliateRepository.class), mock(PartnerRepository.class),
            mock(AuditLogRepository.class), mock(NotificationRepository.class), keycloak, tx);

    @SuppressWarnings("unchecked")
    void txRunsCallback() {
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
    }

    @Test
    void 앱_role이_붙은_고아_유저는_지우고_다시_발급한다() {
        txRunsCallback();
        when(keycloak.enabled()).thenReturn(true);
        when(keycloak.findUserIdByUsername("new-admin")).thenReturn("kc-orphan");
        when(keycloak.hasAppRole("kc-orphan")).thenReturn(true);
        when(keycloak.deleteUser("kc-orphan")).thenReturn(true);
        when(keycloak.createUser(eq("new-admin"), anyString(), eq("crosshub"), anyString())).thenReturn("kc-new");
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var issued = service.issue(ADMIN, new AccountCommandService.IssueRequest("crosshub", "담당자", "new-admin", null, null, null));
        assertThat(issued.loginId()).isEqualTo("new-admin");
        verify(keycloak).deleteUser("kc-orphan");
    }

    @Test
    void 앱과_무관한_Keycloak_유저는_건드리지_않고_loginIdTaken() {
        when(keycloak.enabled()).thenReturn(true);
        when(keycloak.findUserIdByUsername("someone")).thenReturn("kc-other");
        when(keycloak.hasAppRole("kc-other")).thenReturn(false);

        Throwable e = catchThrowable(() -> service.issue(ADMIN,
                new AccountCommandService.IssueRequest("crosshub", "담당자", "someone", null, null, null)));
        assertThat(((AccountException) e).code()).isEqualTo("loginIdTaken");
        verify(keycloak, never()).deleteUser(anyString());
        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void DB_저장이_실패하면_Keycloak_유저를_지운다() {
        when(keycloak.enabled()).thenReturn(true);
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString())).thenReturn("kc-new");
        when(keycloak.deleteUser("kc-new")).thenReturn(false); // 삭제도 실패 — 예외는 원래 것 그대로
        when(tx.execute(any())).thenThrow(new IllegalStateException("db down"));

        Throwable e = catchThrowable(() -> service.issue(ADMIN,
                new AccountCommandService.IssueRequest("crosshub", "담당자", "new-admin", null, null, null)));
        assertThat(e).hasMessage("db down");
        verify(keycloak).deleteUser("kc-new");
    }

    @Test
    void 활성_토글_DB_실패하면_Keycloak도_되돌린다() {
        UserAccount u = mock(UserAccount.class);
        when(u.isActive()).thenReturn(true);
        when(u.getLoginId()).thenReturn("partner-a");
        when(users.findById("u1")).thenReturn(Optional.of(u));
        when(keycloak.enabled()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(tx).executeWithoutResult(any(Consumer.class));

        Throwable e = catchThrowable(() -> service.toggleActive(ADMIN, "u1"));
        assertThat(e).hasMessage("db down");
        verify(keycloak).setEnabled("partner-a", false); // 비활성화 반영
        verify(keycloak).setEnabled("partner-a", true);  // 되돌리기
    }
}
