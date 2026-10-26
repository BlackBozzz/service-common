package com.argent.servicecommon.dictionary.repository;

import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Репозиторий для работы с реестром справочников.
 */
public interface CommonDictionaryRepository
        extends JpaRepository<CommonDictionary, Long> {

    /**
     * Поиск справочника по уникальному коду.
     */
    Optional<CommonDictionary> findByCode(String code);

    /**
     * Поиск справочника по имени Java-сущности.
     */
    Optional<CommonDictionary> findByEntityClass(String entityClass);
}
