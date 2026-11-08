package com.argent.servicecommon.auth.repository;

import com.argent.servicecommon.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий пользователей.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByLogin(String login);

    Page<User> findByLoginContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
            String login, String displayName, Pageable pageable);
}
