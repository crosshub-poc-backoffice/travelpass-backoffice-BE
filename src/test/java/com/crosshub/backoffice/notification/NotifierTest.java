package com.crosshub.backoffice.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.account.UserAccount;
import com.crosshub.backoffice.account.UserAccountRepository;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 알림 생성 — 같은 (사용자, 종류, 출처)는 한 번만, payload 에 type 포함, 받는 사람 계산 */
class NotifierTest {

    NotificationRepository notifications = mock(NotificationRepository.class);
    UserAccountRepository users = mock(UserAccountRepository.class);
    PartnerRepository partners = mock(PartnerRepository.class);
    Notifier notifier = new Notifier(notifications, users, partners, new ObjectMapper());

    @Test
    void 이미_있는_조합은_건너뛰고_payload에_type을_담는다() {
        when(notifications.existsByUserIdAndTypeAndSourceId("u1", "product_status_changed", "p1:live")).thenReturn(true);
        notifier.notify(List.of("u1", "u2", "u2"), "product_status_changed", "p1:live", Map.of("productId", "p1", "status", "live"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, times(1)).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo("u2");
        assertThat(saved.getValue().getType()).isEqualTo("product_status_changed");
        assertThat(saved.getValue().getPayload()).contains("\"type\":\"product_status_changed\"").contains("\"productId\":\"p1\"");
        assertThat(saved.getValue().isRead()).isFalse();
    }

    @Test
    void 받는_사람이_없으면_아무것도_안_한다() {
        notifier.notify(List.of(), "booking_quote_responded", "b1:accepted", Map.of());
        verify(notifications, never()).save(any());
    }

    @Test
    void 소유측은_업체_계정과_대행_연맹_계정() {
        Partner p = mock(Partner.class);
        when(p.getProxyAffiliateId()).thenReturn("affX");
        when(partners.findById("pA")).thenReturn(Optional.of(p));
        when(users.findByRoleAndPartnerIdAndActiveTrue("partner", "pA")).thenReturn(List.of(user("u-p")));
        when(users.findByRoleAndAffiliateIdAndActiveTrue("affiliate", "affX")).thenReturn(List.of(user("u-a")));
        when(users.findByRoleAndActiveTrue("crosshub")).thenReturn(List.of(user("u-c")));

        assertThat(notifier.partnerStakeholders("pA", false, true, true)).containsExactly("u-a", "u-p");
        assertThat(notifier.partnerStakeholders("pA", true, false, false)).containsExactly("u-c");
        assertThat(notifier.partnerUserIds(null)).isEmpty();
    }

    static UserAccount user(String id) {
        UserAccount u = UserAccount.issue("login-" + id, "이름", "partner", null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", id);
        return u;
    }
}
