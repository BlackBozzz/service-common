package com.argent.servicecommon.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Строка занимает не больше {@link #value()} байт в UTF-8 ({@code null} допустим). Нужна, например, для пароля:
 * BCrypt принимает не больше 72 байт, а {@code @Size} считает символы (40 кириллических символов — 80 байт).
 */
@Documented
@Constraint(validatedBy = MaxBytesValidator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE})
@Retention(RUNTIME)
public @interface MaxBytes {

    /** Сообщение для пароля, общее для запросов регистрации, создания пользователя и смены пароля. */
    String PASSWORD_MESSAGE = "Пароль не должен превышать 72 байта в UTF-8";

    /** Наибольшее число байт в UTF-8. */
    int value();

    String message() default "Значение не должно превышать {value} байт в UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
