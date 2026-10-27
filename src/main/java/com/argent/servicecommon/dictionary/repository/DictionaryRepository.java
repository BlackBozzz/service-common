package com.argent.servicecommon.dictionary.repository;

import java.util.List;
import java.util.Optional;

/**
 * Универсальный репозиторий для работы с записями справочников.
 *
 * Позволяет выполнять стандартные CRUD-операции над любой
 * JPA-сущностью без создания отдельного репозитория.
 */
public interface DictionaryRepository {

    /**
     * Находит запись справочника по идентификатору.
     *
     * @param entityClass класс JPA-сущности
     * @param id идентификатор записи
     * @return найденная запись или пустой результат
     */
    <T> Optional<T> findById(Class<T> entityClass, Object id);

    /**
     * Возвращает записи справочника.
     *
     * Пока без фильтрации и пагинации.
     *
     * @param entityClass класс JPA-сущности
     * @return список записей
     */
    <T> List<T> findAll(Class<T> entityClass);

    /**
     * Сохраняет новую запись справочника.
     *
     * @param entity новая JPA-сущность
     * @return сохранённая сущность
     */
    <T> T create(T entity);

    /**
     * Сохраняет изменения существующей записи.
     *
     * @param entity JPA-сущность
     * @return обновлённая сущность
     */
    <T> T update(T entity);

    /**
     * Удаляет существующую запись справочника.
     *
     * @param entity JPA-сущность
     */
    <T> void delete(T entity);
}