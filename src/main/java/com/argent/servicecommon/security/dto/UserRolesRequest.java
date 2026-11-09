package com.argent.servicecommon.security.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Замена ролей пользователя; {@code roleCodes} обязателен (тело без него — 400), пустой список снимает все роли.
 */
public record UserRolesRequest(
        @NotNull(message = "Укажите список ролей; пустой список снимает все роли") List<String> roleCodes
) {
}
