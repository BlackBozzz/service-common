package com.argent.servicecommon.dictionary.exception;

/** Ошибка конфигурации DTO или преобразования полей справочника. */
public class DictionaryMappingException extends RuntimeException {
    public DictionaryMappingException(String message) { super(message); }
    public DictionaryMappingException(String message, Throwable cause) { super(message, cause); }
}
