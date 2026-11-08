package com.argent.servicecommon.auth.dto;

/** Токен для заголовка {@code Authorization: Bearer <token>}. */
public record LoginResponse(String token) {
}
