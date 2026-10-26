package com.argent.servicecommon.dictionary.exception;

/**
 * Исключение, возникающее при ошибках регистрации справочников.
 *
 * Используется при обнаружении конфликтов между
 * Java-сущностями и зарегистрированными справочниками.
 */
public class DictionaryRegistrationException extends RuntimeException {

    /**
     * Создаёт исключение с описанием ошибки.
     *
     * @param message сообщение об ошибке на русском языке
     */
    public DictionaryRegistrationException(String message) {
        super(message);
    }
}
