package com.argent.servicecommon.dictionary.service;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.exception.DictionaryMappingException;
import com.argent.servicecommon.dictionary.mapper.DictionaryDtoMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Типизированная DTO-надстройка над проверенным универсальным CRUD.
 *
 * <p>Создание и PUT-обновление принимают полный DTO. До изменения JPA-сущности
 * выполняется серверная Bean Validation. Для PUT сначала загружается
 * управляемая сущность, затем маппер изменяет разрешённые поля. При успехе
 * Hibernate сохраняет изменения в текущей транзакции.</p>
 */
public class DictionaryDtoCrudService {
    private final DictionaryCrudService crud;
    private final DictionaryDtoMapper mapper;
    private final Validator validator;

    public DictionaryDtoCrudService(DictionaryCrudService crud,
                                    DictionaryDtoMapper mapper, Validator validator) {
        this.crud = Objects.requireNonNull(crud);
        this.mapper = Objects.requireNonNull(mapper);
        this.validator = Objects.requireNonNull(validator);
    }

    @Transactional(readOnly = true)
    public <E, D> D findById(String code, String id, Class<E> entityClass, Class<D> dtoClass) {
        verifyDto(entityClass, dtoClass);
        E entity = crud.findById(code, id, entityClass);
        return mapper.toDto(entity, dtoClass);
    }

    /** Возвращает все записи; для публичного REST требуется постраничный метод. */
    @Transactional(readOnly = true)
    public <E, D> List<D> findAll(String code, Class<E> entityClass, Class<D> dtoClass) {
        verifyDto(entityClass, dtoClass);
        return crud.findAll(code, entityClass).stream()
                .map(entity -> mapper.toDto(entity, dtoClass)).toList();
    }

    @Transactional
    public <E, D> D create(String code, D dto, Class<E> entityClass, Class<D> dtoClass) {
        verifyDto(entityClass, dtoClass);
        requireDto(dto, dtoClass);
        validate(dto);
        E entity = mapper.toEntity(dto, entityClass);
        E created = crud.create(code, entity);
        return mapper.toDto(created, dtoClass);
    }

    /** Обновляет всю представленную DTO-модель, без семантики PATCH. */
    @Transactional
    public <E, D> D update(String code, String id, D dto,
                           Class<E> entityClass, Class<D> dtoClass) {
        verifyDto(entityClass, dtoClass);
        requireDto(dto, dtoClass);
        validate(dto);
        // Возвращаемый объект управляется Hibernate; повторный merge не нужен.
        E managed = crud.findById(code, id, entityClass);
        mapper.updateEntity(dto, managed);
        return mapper.toDto(managed, dtoClass);
    }

    @Transactional
    public void delete(String code, String id) { crud.delete(code, id); }

    private <D> void requireDto(D dto, Class<D> dtoClass) {
        if (dto == null || !dtoClass.isInstance(dto)) {
            throw new IllegalArgumentException("Тело запроса содержит DTO неправильного типа или null.");
        }
    }

    private <D> void validate(D dto) {
        Set<ConstraintViolation<D>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException("DTO не прошёл проверку ограничений.", violations);
        }
    }

    private void verifyDto(Class<?> entityClass, Class<?> dtoClass) {
        Objects.requireNonNull(entityClass, "Класс сущности не может быть null.");
        Objects.requireNonNull(dtoClass, "Класс DTO не может быть null.");
        ArgentDictionary dictionary = entityClass.getAnnotation(ArgentDictionary.class);
        if (dictionary == null || !dictionary.dtoClass().equals(dtoClass)) {
            throw new DictionaryMappingException(
                    "DTO '%s' не соответствует @ArgentDictionary сущности '%s'."
                            .formatted(dtoClass.getName(), entityClass.getName()));
        }
    }
}
