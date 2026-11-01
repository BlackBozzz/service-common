package com.argent.servicecommon.dictionary.mapper;

/** Универсальный контракт преобразования зарегистрированных справочников и их DTO. */
public interface DictionaryDtoMapper {
    /** Преобразует существующую сущность в DTO указанного типа. */
    <E, D> D toDto(E entity, Class<D> dtoClass);

    /** Создаёт новую сущность из полного DTO без создания связанных записей. */
    <E, D> E toEntity(D dto, Class<E> entityClass);

    /** Заменяет разрешённые значения управляемой сущности значениями полного DTO. */
    <E, D> void updateEntity(D dto, E entity);

    /** Проверяет структуру DTO и связи без выполнения запросов к базе. */
    void validateStructure(Class<?> entityClass, Class<?> dtoClass);
}
