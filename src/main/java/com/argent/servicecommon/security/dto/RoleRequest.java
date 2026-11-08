package com.argent.servicecommon.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Создание или изменение роли (код — в пути); {@code grants} отсутствует или {@code null} — роль без прав. */
public record RoleRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        JsonNode grants
) {
}
