package com.crosshub.backoffice.partner;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, String> {
    List<Partner> findAll(Sort sort);
}
