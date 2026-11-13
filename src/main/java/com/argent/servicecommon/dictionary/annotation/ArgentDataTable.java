package com.argent.servicecommon.dictionary.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Публикует существующую JPA-сущность в разделе «Данные» админки.
 * Таблица создаётся и изменяется ТОЛЬКО миграциями приложения (Flyway),
 * в отличие от @ArgentDictionary библиотека никогда не создаёт DDL.
 * Таблица должна быть помечена @Table(name = "...") и иметь простой @Id.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ArgentDataTable {
    /** Название плитки и таблицы. */
    String name();
    /** Группа каталога, например «Управление документацией». */
    String group() default "Таблицы";
    /** DTO с полями, доступными в админке. */
    Class<?> dtoClass();
    /** Позволить редактирование при наличии table:<name>:write. */
    boolean editable() default true;
}
