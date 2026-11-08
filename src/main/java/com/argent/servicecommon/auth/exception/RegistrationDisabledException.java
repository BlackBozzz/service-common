package com.argent.servicecommon.auth.exception;

import org.springframework.security.access.AccessDeniedException;

/** Регистрация выключена ({@code argent.auth.registration.enabled=false}); ответ 403 с этим сообщением. */
public class RegistrationDisabledException extends AccessDeniedException {

    public RegistrationDisabledException() {
        super("Регистрация отключена");
    }
}
