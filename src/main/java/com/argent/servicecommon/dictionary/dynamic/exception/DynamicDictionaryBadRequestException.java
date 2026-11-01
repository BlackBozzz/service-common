package com.argent.servicecommon.dictionary.dynamic.exception;

/** Некорректные параметры запроса к динамическому справочнику. */
public class DynamicDictionaryBadRequestException extends IllegalArgumentException {
    public DynamicDictionaryBadRequestException(String message) { super(message); }
    public DynamicDictionaryBadRequestException(String message, Throwable cause) { super(message, cause); }
}
