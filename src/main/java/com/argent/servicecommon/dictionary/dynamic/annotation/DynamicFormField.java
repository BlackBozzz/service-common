package com.argent.servicecommon.dictionary.dynamic.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Дополнительные параметры отображения поля DTO в динамической форме.
 *
 * <p>Аннотация не заменяет Jakarta Validation и не определяет права доступа.
 * Запрет записи полей задаётся существующими серверными правилами маппера.</p>
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DynamicFormField {
    /** Тип элемента формы. AUTO означает автоматический выбор. */
    Control control() default Control.AUTO;

    /** Подсказка в поле ввода. */
    String placeholder() default "";

    /** Дополнительная подсказка для пользователя. */
    String help() default "";

    enum Control {
        AUTO, TEXT, TEXTAREA, NUMBER, CHECKBOX, DATE, TIME, DATETIME,
        SELECT, REFERENCE, MULTI_REFERENCE, HIDDEN
    }
}
