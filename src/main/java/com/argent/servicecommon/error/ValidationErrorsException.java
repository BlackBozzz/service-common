package com.argent.servicecommon.error;

import java.util.Map;

/**
 * Ошибка ручной валидации с привязкой сообщений к полям.
 */
public class ValidationErrorsException extends RuntimeException {

    private final Map<String, String> errors;

    public ValidationErrorsException(Map<String, String> errors) {
        super("Ошибка валидации");
        this.errors = Map.copyOf(errors);
    }

    /** Сообщения об ошибках по именам полей. */
    public Map<String, String> getErrors() {
        return errors;
    }
}
