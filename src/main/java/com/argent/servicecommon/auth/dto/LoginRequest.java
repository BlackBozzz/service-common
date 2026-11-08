package com.argent.servicecommon.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Запрос входа.
 *
 * @param login    логин; пробелы по краям отбрасываются
 * @param password пароль; не нужен, если вход без пароля включён
 */
public record LoginRequest(@NotBlank String login, String password) {
}
