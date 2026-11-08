package com.argent.servicecommon.auth.dto;

import java.util.UUID;

/**
 * Текущий пользователь.
 *
 * @param id          идентификатор
 * @param login       логин
 * @param displayName отображаемое имя
 * @param grants      эффективные гранты пользователя (объединение грантов его ролей)
 */
public record CurrentUserDto(UUID id, String login, String displayName, GrantsDto grants) {
}
