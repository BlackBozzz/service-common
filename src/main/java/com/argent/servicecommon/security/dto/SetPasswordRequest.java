package com.argent.servicecommon.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на установку пароля пользователю.
 */
public record SetPasswordRequest(
        @NotBlank @Size(min = 6, max = 72) String password
) {
}
