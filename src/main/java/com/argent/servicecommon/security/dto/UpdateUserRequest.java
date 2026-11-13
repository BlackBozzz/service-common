package com.argent.servicecommon.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на изменение пользователя.
 */
@Schema(title = "Изменение пользователя", description = "Параметры изменения учётной записи")
public record UpdateUserRequest(
        @Schema(title = "Имя пользователя")
        @NotBlank @Size(max = 255) String displayName,
        @Schema(title = "Активен") boolean enabled,
        @Schema(title = "Электронная почта")
        @Email @Size(max = 254) String email
) {
    /** При отсутствии email в запросе адрес пользователя не меняется. */
    public UpdateUserRequest(String displayName, boolean enabled) {
        this(displayName, enabled, null);
    }
}
