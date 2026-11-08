package com.argent.servicecommon.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос самостоятельной регистрации.
 *
 * @param login       логин; пробелы по краям отбрасываются
 * @param displayName отображаемое имя
 * @param password    пароль
 */
public record RegisterRequest(
        @NotBlank @Size(max = 100) String login,
        @NotBlank @Size(max = 255) String displayName,
        @NotBlank @Size(min = 6, max = 72) String password
) {
}
