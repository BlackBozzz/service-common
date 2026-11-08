package com.argent.servicecommon.security.dto;

import com.argent.servicecommon.auth.dto.GrantsDto;

import java.util.UUID;

/** Роль в администрировании; системную ({@code system}) нельзя удалить, её гранты всегда — набор администратора. */
public record RoleDto(
        UUID id,
        String code,
        String name,
        String description,
        boolean system,
        GrantsDto grants
) {
}
