package com.crosshub.backoffice.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginLogRepository extends JpaRepository<LoginLog, String> {
    /** 해당 유저의 가장 최근 로그인 성공 1건. */
    Optional<LoginLog> findFirstByUserIdAndEventOrderByCreatedAtDesc(String userId, String event);
}
