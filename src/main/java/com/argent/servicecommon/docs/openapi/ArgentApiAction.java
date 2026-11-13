package com.argent.servicecommon.docs.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Документирует требуемое действие. НЕ выполняет проверку доступа:
 * метод также должен быть защищён @PreAuthorize или эквивалентным механизмом.
 * Роли вычисляются из текущих грантов common_role, а не указываются в аннотации.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ArgentApiAction {
    String value();
}
