package com.crosshub.backoffice.partner;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, String> {
    List<Partner> findAll(Sort sort);
    List<Partner> findByProxyAffiliateId(String proxyAffiliateId);
    List<Partner> findByProxyAffiliateIdIsNullOrderByNameAsc();
    List<Partner> findByIdInOrderByNameAsc(Collection<String> ids);
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, String id);
}
