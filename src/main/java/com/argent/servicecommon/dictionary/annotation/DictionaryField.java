package com.argent.servicecommon.dictionary.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Описание поля DTO для автоматической формы справочника.
 *
 * <p>Ограничения значений задаются стандартными аннотациями Jakarta Validation.
 * Признак readOnly запрещает перенос поля из DTO в сущность, но не чтение поля.</p>
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DictionaryField {
    String label() default "";
    String description() default "";
    int order() default 0;
    boolean readOnly() default false;
    boolean visible() default true;
}
