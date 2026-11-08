package com.argent.servicecommon.security.dto;

import com.argent.servicecommon.auth.dto.GrantsDto;

import java.util.UUID;

/**
 * Роль в интерфейсе администрирования.
 *
 * @param system системная роль: её нельзя удалить, а гранты всегда равны набору администратора
 * @param grants гранты роли
 */
public record RoleDto(
        UUID id,
        String code,
        String name,
        String description,
        boolean system,
        GrantsDto grants
) {
}
