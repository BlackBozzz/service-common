package com.argent.servicecommon.auth.repository;

import com.argent.servicecommon.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий пользователей.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByLogin(String login);

    /** Поиск адреса без учёта регистра для проверки уникальности перед сохранением. */
    Optional<User> findByEmailIgnoreCase(String email);

    /** Адресаты по списку идентификаторов. */
    List<User> findByIdIn(Collection<UUID> ids);

    /** Все активные пользователи с заданным email. */
    List<User> findByEnabledTrueAndEmailIsNotNull();

    Page<User> findByLoginContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
            String login, String displayName, Pageable pageable);
}
