package com.argent.servicecommon.dictionary.dynamic.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Отмечает строковое поле DTO, используемое как подпись записи в списках выбора.
 * Допускается не более одного такого поля на DTO справочника.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DynamicDictionaryDisplay { }
