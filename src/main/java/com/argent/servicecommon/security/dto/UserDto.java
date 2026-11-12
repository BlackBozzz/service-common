package com.argent.servicecommon.security.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Пользователь в администрировании; {@code roles} — коды по алфавиту. */
public record UserDto(
        UUID id,
        String login,
        String displayName,
        String email,
        boolean enabled,
        boolean hasPassword,
        List<String> roles,
        Instant createdAt
) {
    /** Совместимость с Java-кодом, создающим DTO без email. */
    public UserDto(UUID id, String login, String displayName, boolean enabled,
                   boolean hasPassword, List<String> roles, Instant createdAt) {
        this(id, login, displayName, null, enabled, hasPassword, roles, createdAt);
    }
}
