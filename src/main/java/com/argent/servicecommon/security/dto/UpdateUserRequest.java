package com.argent.servicecommon.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на изменение пользователя.
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 255) String displayName,
        boolean enabled
) {
}
