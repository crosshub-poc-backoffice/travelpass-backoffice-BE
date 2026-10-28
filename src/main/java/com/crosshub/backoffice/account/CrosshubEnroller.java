package com.crosshub.backoffice.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 크로스허브 직원 첫 로그인 자동 등록 (JIT).
 * 크로스허브 직원 계정은 Keycloak 콘솔에서 만들어 /crosshub 그룹에 넣는다. 그 직원이 처음 로그인하면
 * CallerService 가 DB 에 없는 걸 보고 여기서 User 를 만든다 — 백오피스에서 따로 "발급"하지 않는다.
 * /crosshub 에 넣을 수 있는 건 콘솔 관리자뿐이라(svc 는 조회만, keycloak-auth realm JSON) 그룹 소속을 믿어도 된다.
 * 감사 기록은 발급과 같은 account_issue 로, 행위자는 본인이다.
 * 요청자 확인은 읽기 전용 트랜잭션 안에서도 불리므로(GET /me 등) 항상 새 트랜잭션에서 쓴다.
 */
@Component
public class CrosshubEnroller {

    private static final Logger log = LoggerFactory.getLogger(CrosshubEnroller.class);
    private final UserAccountRepository users;
    private final AuditLogRepository audits;

    public CrosshubEnroller(UserAccountRepository users, AuditLogRepository audits) {
        this.users = users;
        this.audits = audits;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserAccount enroll(String loginId, String name, String sub) {
        UserAccount u = users.saveAndFlush(UserAccount.issue(loginId, name, "crosshub", null, null, sub));
        audits.save(AuditLog.of(u.getId(), "account_issue", null, u.getId(), u.getLoginId()));
        log.info("[auth] 크로스허브 첫 로그인 자동 등록 — loginId={} sub={}", loginId, sub);
        return u;
    }
}
