package com.crosshub.backoffice.partner;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AffiliateRepository extends JpaRepository<Affiliate, String> {
    List<Affiliate> findAll(Sort sort);
    boolean existsByNameIgnoreCase(String name);
}
