package com.argent.servicecommon.security.dto;

import com.argent.servicecommon.validation.MaxBytes;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Создание пользователя; {@code password} от 6 до 72 символов и не длиннее 72 байт в UTF-8, без него пользователь создаётся без пароля. */
@Schema(title = "Создание пользователя", description = "Параметры регистрации учётной записи администратором")
public record CreateUserRequest(
        @Schema(title = "Логин", example = "newoperator")
        @NotBlank @Size(max = 100) String login,
        @Schema(title = "Имя пользователя", example = "Новый оператор")
        @NotBlank @Size(max = 255) String displayName,
        @Schema(title = "Пароль", description = "Необязательный пароль длиной 6–72 символа", accessMode = Schema.AccessMode.WRITE_ONLY)
        @Size(min = 6, max = 72)
        @MaxBytes(value = 72, message = MaxBytes.PASSWORD_MESSAGE) String password,
        @Schema(title = "Электронная почта", example = "operator@example.org")
        @Email @Size(max = 254) String email
) {
    /** Сохраняет совместимость с вызовами конструктора без email. */
    public CreateUserRequest(String login, String displayName, String password) {
        this(login, displayName, password, null);
    }
}
