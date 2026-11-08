package com.argent.servicecommon.sequence;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ограничение Jakarta Validation: строка — допустимый шаблон номера ({@link PatternValidator}).
 *
 * <p>{@code null} считается допустимым — обязательность проверяется {@code @NotBlank}.</p>
 */
@Documented
@Constraint(validatedBy = SequencePatternValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SequencePattern {

    /** Сообщение по умолчанию; валидатор подставляет текст с самим шаблоном. */
    String message() default "Недопустимый шаблон номера.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
