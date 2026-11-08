package com.argent.servicecommon.auth.dto;

import java.util.UUID;

/** Текущий пользователь; {@code grants} — объединение грантов его ролей. */
public record CurrentUserDto(UUID id, String login, String displayName, GrantsDto grants) {
}
