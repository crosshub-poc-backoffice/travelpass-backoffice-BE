package com.crosshub.backoffice.account;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessLogRepository extends JpaRepository<AccessLog, String> {
    @Modifying
    @Query("delete from AccessLog a where a.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
