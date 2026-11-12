package com.argent.servicecommon.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на изменение пользователя.
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 255) String displayName,
        boolean enabled,
        @Email @Size(max = 254) String email
) {
    /** При отсутствии email в запросе адрес пользователя не меняется. */
    public UpdateUserRequest(String displayName, boolean enabled) {
        this(displayName, enabled, null);
    }
}
