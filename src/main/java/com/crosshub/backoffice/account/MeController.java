package com.crosshub.backoffice.account;

import com.crosshub.backoffice.partner.Partner;
import com.crosshub.backoffice.partner.PartnerRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/v1/me — 로그인한 직원의 "업무상 신원". FE(BFF)가 세션을 만들 때와 요청마다 이걸로 역할·업체 범위를 받는다.
 * FE 는 리소스 DB 를 직접 읽지 않는다는 원칙(docs/auth-architecture.md 원칙 6)의 첫 조각.
 *
 * 응답은 FE 의 SessionUser 와 같은 모양. managedPartners 는 요청마다 새로 계산한다 — 업체 등록·운영방식 변경이
 * 다시 로그인하지 않아도 반영되도록 (세션에 저장하지 않는다).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public record PartnerRef(String id, String name) {}

    public record MeResponse(String id, String loginId, String name, String role,
                             String affiliateId, String partnerId, String orgName,
                             List<PartnerRef> managedPartners) {}

    private final CallerService callers;
    private final UserAccountRepository users;
    private final PartnerRepository partners;

    public MeController(CallerService callers, UserAccountRepository users, PartnerRepository partners) {
        this.callers = callers;
        this.users = users;
        this.partners = partners;
    }

    @GetMapping
    public MeResponse me() {
        Caller c = callers.current();   // 토큰 role ≠ DB role 이면 여기서 403 roleMismatch
        UserAccount u = users.findById(c.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "백오피스에 등록되지 않은 계정입니다."));
        return new MeResponse(u.getId(), u.getLoginId(), u.getName(), u.getRole(),
                u.getAffiliateId(), u.getPartnerId(), u.getOrgName(), managedPartners(c));
    }

    /** FE 의 옛 findManagedPartners(lib/server/auth/session.ts)와 같은 규칙. crosshub 는 전체, affiliate 는 대행 업체, partner 는 자기 업체 */
    private List<PartnerRef> managedPartners(Caller c) {
        List<Partner> list;
        if (c.isCrosshub()) {
            list = partners.findAll(Sort.by("name"));
        } else if (c.isAffiliate()) {
            list = c.affiliateId() == null ? List.of() : partners.findByProxyAffiliateId(c.affiliateId());
        } else {
            list = c.partnerId() == null ? List.of() : partners.findById(c.partnerId()).map(List::of).orElse(List.of());
        }
        return list.stream().sorted(java.util.Comparator.comparing(Partner::getName)).map(p -> new PartnerRef(p.getId(), p.getName())).toList();
    }
}
