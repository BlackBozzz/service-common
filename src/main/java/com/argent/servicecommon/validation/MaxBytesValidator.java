package com.argent.servicecommon.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

/** Проверка {@link MaxBytes}: длина строки в байтах UTF-8. */
public class MaxBytesValidator implements ConstraintValidator<MaxBytes, CharSequence> {

    private int max;

    @Override
    public void initialize(MaxBytes annotation) {
        this.max = annotation.value();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
