package com.argent.servicecommon.auth.dto;

/**
 * Настройки входа, нужные клиенту до аутентификации.
 *
 * @param passwordRequired    требуется ли пароль при входе
 * @param registrationEnabled разрешена ли самостоятельная регистрация
 */
public record AuthConfigDto(boolean passwordRequired, boolean registrationEnabled) {
}
