package com.argent.servicecommon.security.dto;

import com.argent.servicecommon.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на установку пароля пользователю.
 */
public record SetPasswordRequest(
        @NotBlank @Size(min = 6, max = 72)
        @MaxBytes(value = 72, message = MaxBytes.PASSWORD_MESSAGE) String password
) {
}
