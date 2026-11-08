package com.argent.servicecommon.auth.repository;

import com.argent.servicecommon.auth.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Репозиторий сессий пользователей.
 */
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    List<UserSession> findByUserIdAndRevokedFalse(UUID userId);
}
