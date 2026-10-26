package com.argent.servicecommon.dictionary.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает JPA-сущность как справочник.
 *
 * Сущности с данной аннотацией автоматически обнаруживаются
 * библиотекой и регистрируются в реестре справочников.
 *
 * Название таблицы и уникальный код справочника определяются
 * аннотацией JPA @Table.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ArgentDictionary {
    /**
     * Отображаемое название справочника.
     */
    String name();

    /**
     * DTO-класс справочника.
     * Используется для формирования метаданных динамических форм.
     */
    Class<?> dtoClass();
}
