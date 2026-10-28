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

/** 계정 발급(크로스허브는 발급 없음)·롤백, 같은 username 거절, 크로스허브 대상 초기화·토글, 활성 토글 되돌리기 */
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

    /** 새 연맹을 만들며 발급하는 요청 — 소속 조회 없이 Keycloak 생성까지 간다 */
    static AccountCommandService.IssueRequest newAffiliate(String loginId) {
        return new AccountCommandService.IssueRequest("affiliate", null, loginId, null, null, "새 연맹");
    }

    @Test
    void 크로스허브는_발급하지_않는다_첫_로그인_때_자동_등록() {
        when(keycloak.enabled()).thenReturn(true);
        Throwable e = catchThrowable(() -> service.issue(ADMIN,
                new AccountCommandService.IssueRequest("crosshub", "담당자", "new-admin", null, null, null)));
        assertThat(((AccountException) e).code()).isEqualTo("crosshubAutoEnrolled");
        verify(keycloak, never()).findUserIdByUsername(anyString());
        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString());
        verify(users, never()).save(any());
    }

    @Test
    void 같은_username이_Keycloak에_있으면_지우지_않고_loginIdTaken() {
        when(keycloak.enabled()).thenReturn(true);
        when(keycloak.findUserIdByUsername("leftover")).thenReturn("kc-existing");

        Throwable e = catchThrowable(() -> service.issue(ADMIN, newAffiliate("leftover")));
        assertThat(((AccountException) e).code()).isEqualTo("loginIdTaken");
        verify(keycloak, never()).deleteUser(anyString());
        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void svc에게_안_보이는_같은_username이_있으면_loginIdTaken() {
        when(keycloak.enabled()).thenReturn(true);
        when(keycloak.findUserIdByUsername("hidden")).thenReturn(null);
        when(keycloak.createUser(eq("hidden"), anyString(), anyString(), anyString()))
                .thenThrow(new KeycloakAdminClient.UsernameTakenException("hidden", null));

        Throwable e = catchThrowable(() -> service.issue(ADMIN, newAffiliate("hidden")));
        assertThat(((AccountException) e).code()).isEqualTo("loginIdTaken");
        verify(users, never()).save(any());
    }

    @Test
    void DB_저장이_실패하면_Keycloak_유저를_지운다() {
        when(keycloak.enabled()).thenReturn(true);
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString())).thenReturn("kc-new");
        when(keycloak.deleteUser("kc-new")).thenReturn(false); // 삭제도 실패 — 예외는 원래 것 그대로
        when(tx.execute(any())).thenThrow(new IllegalStateException("db down"));

        Throwable e = catchThrowable(() -> service.issue(ADMIN, newAffiliate("new-affiliate")));
        assertThat(e).hasMessage("db down");
        verify(keycloak).deleteUser("kc-new");
    }

    @Test
    void 크로스허브_계정_비번_초기화는_막는다() {
        UserAccount u = mock(UserAccount.class);
        when(u.getRole()).thenReturn("crosshub");
        when(users.findById("u2")).thenReturn(Optional.of(u));

        Throwable e = catchThrowable(() -> service.resetPassword(ADMIN, "u2"));
        assertThat(((AccountException) e).code()).isEqualTo("crosshubManagedInConsole");
        verify(keycloak, never()).resetPassword(anyString(), any(), any(), anyString());
    }

    @Test
    void 크로스허브_계정_활성_토글은_DB만_바꾼다() {
        UserAccount u = mock(UserAccount.class);
        when(u.isActive()).thenReturn(true);
        when(u.getRole()).thenReturn("crosshub");
        when(users.findById("u2")).thenReturn(Optional.of(u));

        assertThat(service.toggleActive(ADMIN, "u2")).isFalse();
        verify(tx).executeWithoutResult(any());
        verify(keycloak, never()).setEnabled(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(keycloak, never()).enabled();
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
