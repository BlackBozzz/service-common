package com.argent.servicecommon.security.dto;

import com.argent.servicecommon.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Создание пользователя; {@code password} от 6 до 72 символов и не длиннее 72 байт в UTF-8, без него пользователь создаётся без пароля. */
public record CreateUserRequest(
        @NotBlank @Size(max = 100) String login,
        @NotBlank @Size(max = 255) String displayName,
        @Size(min = 6, max = 72)
        @MaxBytes(value = 72, message = MaxBytes.PASSWORD_MESSAGE) String password
) {
}
