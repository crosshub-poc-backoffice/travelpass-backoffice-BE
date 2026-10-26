package com.crosshub.backoffice.notification;

import com.crosshub.backoffice.account.UserAccount;
import com.crosshub.backoffice.account.UserAccountRepository;
import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 저장형(이벤트) 알림 생성 + 받는 사람 계산. FE lib/server/notifications/{service.createNotifications, recipients} 포트.
 * BE 의 쓰기(상태 전환·변경요청·견적 응답)가 일어난 자리에서 같은 트랜잭션 안에 만든다.
 *
 * - payload 엔 렌더링된 텍스트를 넣지 않는다 — 텍스트는 받는 사람이 자기 화면 언어로 그 자리에서 만든다.
 * - (userId, type, sourceId) 유니크: 같은 일이 두 번 와도 한 번만 남는다. 트랜잭션 안에서 제약 위반을 내면 전체가
 *   abort 되므로(Postgres), 미리 있는지 보고 건너뛴다.
 * - 알림 생성 실패가 본 작업(승인·전환)을 되돌리면 안 된다 → 호출부가 아니라 여기서 삼키고 로그만.
 */
@Service
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final NotificationRepository notifications;
    private final UserAccountRepository users;
    private final PartnerRepository partners;
    private final ObjectMapper om;

    public Notifier(NotificationRepository notifications, UserAccountRepository users, PartnerRepository partners, ObjectMapper om) {
        this.notifications = notifications;
        this.users = users;
        this.partners = partners;
        this.om = om;
    }

    /** payload 의 type 은 호출부가 넣지 않는다 — 여기서 type 을 같이 담는다 (FE NotificationPayload 와 같은 모양) */
    public void notify(List<String> userIds, String type, String sourceId, Map<String, Object> payload) {
        if (userIds.isEmpty()) return;
        try {
            Map<String, Object> full = new LinkedHashMap<>();
            full.put("type", type);
            full.putAll(payload);
            String json = om.writeValueAsString(full);
            for (String userId : new LinkedHashSet<>(userIds)) {
                if (notifications.existsByUserIdAndTypeAndSourceId(userId, type, sourceId)) continue;
                notifications.save(Notification.of(userId, type, sourceId, json));
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[notifications] {} 알림 생성 실패 ({})", type, sourceId, e);
        }
    }

    // ── 받는 사람 ──────────────────────────────────────────

    /** 크로스허브 활성 계정 전부 */
    public List<String> crosshubUserIds() {
        return ids(users.findByRoleAndActiveTrue("crosshub"));
    }

    /** 그 업체의 활성 계정 */
    public List<String> partnerUserIds(String partnerId) {
        if (partnerId == null) return List.of();
        return ids(users.findByRoleAndPartnerIdAndActiveTrue("partner", partnerId));
    }

    /**
     * 상품 소유측: 업체 계정 + (대행 업체면) 대행 연맹 계정. 크로스허브는 includeCrosshub 일 때만.
     * (FE partnerStakeholders — 지금 호출부는 전부 업체+연맹, 크로스허브 제외)
     */
    public List<String> partnerStakeholders(String partnerId, boolean includeCrosshub, boolean includeAffiliate, boolean includePartner) {
        Set<String> out = new LinkedHashSet<>();
        if (includeCrosshub) out.addAll(crosshubUserIds());
        if (includeAffiliate && partnerId != null) {
            String affiliateId = partners.findById(partnerId).map(Partner::getProxyAffiliateId).orElse(null);
            if (affiliateId != null) out.addAll(ids(users.findByRoleAndAffiliateIdAndActiveTrue("affiliate", affiliateId)));
        }
        if (includePartner) out.addAll(partnerUserIds(partnerId));
        return new ArrayList<>(out);
    }

    private static List<String> ids(List<UserAccount> rows) {
        return rows.stream().map(UserAccount::getId).toList();
    }
}
