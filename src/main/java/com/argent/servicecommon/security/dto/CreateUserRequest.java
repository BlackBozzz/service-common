package com.argent.servicecommon.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на создание пользователя.
 *
 * @param password пароль от 6 до 72 символов; если не задан, пользователь создаётся без пароля
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 100) String login,
        @NotBlank @Size(max = 255) String displayName,
        @Size(min = 6, max = 72) String password
) {
}
