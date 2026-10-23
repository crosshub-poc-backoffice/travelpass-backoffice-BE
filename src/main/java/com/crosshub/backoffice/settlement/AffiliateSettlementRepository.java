package com.crosshub.backoffice.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AffiliateSettlementRepository extends JpaRepository<AffiliateSettlement, String> {
    List<AffiliateSettlement> findByPeriodAndAffiliateIdIn(String period, Collection<String> affiliateIds);
    Optional<AffiliateSettlement> findByAffiliateIdAndPeriodAndCurrency(String affiliateId, String period, String currency);
}
