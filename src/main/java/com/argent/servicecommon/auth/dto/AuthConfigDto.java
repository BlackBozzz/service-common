package com.argent.servicecommon.auth.dto;

/** Настройки входа, нужные клиенту до аутентификации. */
public record AuthConfigDto(boolean passwordRequired, boolean registrationEnabled) {
}
