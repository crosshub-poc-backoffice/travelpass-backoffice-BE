package com.crosshub.backoffice.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, String> {
    List<Settlement> findByPeriodInAndPartnerIdIn(Collection<String> periods, Collection<String> partnerIds);
    Optional<Settlement> findByPartnerIdAndPeriod(String partnerId, String period);
}
