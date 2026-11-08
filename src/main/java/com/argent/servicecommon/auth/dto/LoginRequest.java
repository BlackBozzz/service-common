package com.argent.servicecommon.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Запрос входа; пробелы по краям логина отбрасываются, пароль не нужен в режиме без пароля. */
public record LoginRequest(@NotBlank String login, String password) {
}
