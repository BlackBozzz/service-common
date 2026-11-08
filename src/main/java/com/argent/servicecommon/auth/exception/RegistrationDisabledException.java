package com.argent.servicecommon.auth.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * Самостоятельная регистрация выключена ({@code argent.auth.registration.enabled=false}).
 *
 * <p>Обработчик ошибок возвращает 403 и передаёт сообщение клиенту.</p>
 */
public class RegistrationDisabledException extends AccessDeniedException {

    public RegistrationDisabledException() {
        super("Регистрация отключена");
    }
}
