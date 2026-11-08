package com.argent.servicecommon.security.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Пользователь в интерфейсе администрирования.
 *
 * @param hasPassword задан ли пароль
 * @param roles       коды ролей по алфавиту
 */
public record UserDto(
        UUID id,
        String login,
        String displayName,
        boolean enabled,
        boolean hasPassword,
        List<String> roles,
        Instant createdAt
) {
}
