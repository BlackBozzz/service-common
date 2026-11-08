package com.argent.servicecommon.security.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * Отказ по правилам администрирования грантов: попытка выдать права, которых нет у вызывающего,
 * или управлять пользователем с более широкими правами.
 *
 * <p>Обработчик ошибок возвращает 403 и передаёт сообщение клиенту.</p>
 */
public class GrantsAccessDeniedException extends AccessDeniedException {

    public GrantsAccessDeniedException(String message) {
        super(message);
    }
}
