package com.argent.servicecommon.auth.dto;

/**
 * Ответ на вход или регистрацию.
 *
 * @param token токен для заголовка {@code Authorization: Bearer <token>}
 */
public record LoginResponse(String token) {
}
