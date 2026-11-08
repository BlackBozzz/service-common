package com.argent.servicecommon.sequence;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Валидатор {@link SequencePattern}: пробное форматирование через {@link PatternValidator}.
 *
 * <p>Сообщение об ошибке совпадает с текстом {@link PatternValidator#validate(String)}.</p>
 */
public class SequencePatternValidator implements ConstraintValidator<SequencePattern, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || PatternValidator.isValid(value)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        // Шаблон экранируется: в EL-интерполяции сообщений символы '$', '{', '}' и '\' специальные.
        String message = ("Недопустимый шаблон номера: " + value)
                .replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("$", "\\$");
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
