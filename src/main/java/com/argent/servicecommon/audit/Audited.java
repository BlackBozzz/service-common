package com.argent.servicecommon.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Включает аудит изменений сущности: вставка, удаление и изменение каждого поля пишутся в
 * {@code common_audit_entry}. Сущности без аннотации не аудируются.
 *
 * <pre>{@code
 * @Entity
 * @Audited
 * @Table(name = "orders")
 * public class Order { ... }
 * }</pre>
 *
 * <p>Тип сущности в журнале — имя таблицы из {@code @Table(name)}; по нему же проверяется право
 * чтения журнала ({@code table:<имя>:read}).</p>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Audited {
}
