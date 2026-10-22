package com.crosshub.backoffice.payment;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PgSettingRepository extends JpaRepository<PgSetting, String> {
    List<PgSetting> findAll(Sort sort);
}
