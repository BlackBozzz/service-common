package com.argent.servicecommon.dictionary.dynamic.exception;

/** Запрошенный код справочника отсутствует в реестре. */
public class DynamicDictionaryNotFoundException extends RuntimeException {
    public DynamicDictionaryNotFoundException(String code) {
        super("Справочник '%s' не зарегистрирован.".formatted(code));
    }
}
