package com.argent.servicecommon.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Тело запроса на создание или изменение роли; код роли передаётся в пути и не меняется.
 *
 * @param grants гранты роли вида {@code {"tables": {...}, "actions": [...]}}; отсутствующее значение
 *               или {@code null} означает роль без прав
 */
public record RoleRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        JsonNode grants
) {
}
