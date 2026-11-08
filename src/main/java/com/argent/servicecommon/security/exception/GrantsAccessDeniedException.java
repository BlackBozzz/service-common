package com.argent.servicecommon.security.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * Отказ по грантам (выдать права шире своих, управлять пользователем с большими правами, действие перехода,
 * запись вложения); ответ 403 с этим сообщением.
 */
public class GrantsAccessDeniedException extends AccessDeniedException {

    public GrantsAccessDeniedException(String message) {
        super(message);
    }
}
