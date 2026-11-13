package com.argent.servicecommon.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Пользователь в администрировании; {@code roles} — коды по алфавиту. */
@Schema(title = "Пользователь", description = "Учётная запись пользователя и назначенные роли")
public record UserDto(
        @Schema(title = "Идентификатор", description = "Уникальный UUID пользователя") UUID id,
        @Schema(title = "Логин", example = "operator") String login,
        @Schema(title = "Имя пользователя", example = "Оператор склада") String displayName,
        @Schema(title = "Электронная почта", example = "operator@example.org") String email,
        @Schema(title = "Активен") boolean enabled,
        @Schema(title = "Пароль установлен") boolean hasPassword,
        @Schema(title = "Назначенные роли", description = "Коды назначенных ролей") List<String> roles,
        @Schema(title = "Дата создания") Instant createdAt
) {
    /** Совместимость с Java-кодом, создающим DTO без email. */
    public UserDto(UUID id, String login, String displayName, boolean enabled,
                   @Schema(title = "Пароль установлен") boolean hasPassword, List<String> roles, Instant createdAt) {
        this(id, login, displayName, null, enabled, hasPassword, roles, createdAt);
    }
}
