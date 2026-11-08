package com.argent.servicecommon.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Скрывает значение поля аудируемой сущности: в журнал пишется только факт изменения, прежнее и
 * новое значения заменяются на {@code ***}. Подходит для паролей, токенов и персональных данных.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuditIgnore {
}
