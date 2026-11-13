package com.argent.servicecommon.security.dto;

import com.argent.servicecommon.auth.dto.GrantsDto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Роль в администрировании; системную ({@code system}) нельзя удалить, её гранты всегда — набор администратора. */
@Schema(title = "Роль", description = "Роль системы и её разрешения")
public record RoleDto(
        @Schema(title = "Идентификатор роли") UUID id,
        @Schema(title = "Код роли", example = "ADMIN") String code,
        @Schema(title = "Название роли", example = "Администратор") String name,
        @Schema(title = "Описание роли") String description,
        @Schema(title = "Системная роль") boolean system,
        @Schema(title = "Разрешения", description = "Разрешённые таблицы и действия") GrantsDto grants
) {
}
