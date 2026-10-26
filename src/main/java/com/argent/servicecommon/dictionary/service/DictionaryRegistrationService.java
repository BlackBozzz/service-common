package com.argent.servicecommon.dictionary.service;

import com.argent.servicecommon.dictionary.dto.DictionaryMetadataDto;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.exception.DictionaryRegistrationException;
import com.argent.servicecommon.dictionary.generator.DictionarySchemaGenerator;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Координирует регистрацию справочников при запуске приложения.
 *
 * Таблицы, последовательности, записи реестра и обновление метаданных
 * объединяются одной транзакцией PostgreSQL.
 */
@Slf4j
@RequiredArgsConstructor
public class DictionaryRegistrationService {
    /**
     * Идентификатор блокировки регистрации справочников.
     * Все экземпляры сервиса должны использовать одинаковое значение.
     */
    private static final long DICTIONARY_REGISTRATION_LOCK_KEY = 7777777777L;

    private final DictionaryScannerService scanner;
    private final CommonDictionaryRepository repository;
    private final DictionarySchemaGenerator generator;
    private final JdbcTemplate jdbc;

    /**
     * Выполняет регистрацию в одной транзакции.
     *
     * Advisory lock сериализует одновременный запуск двух экземпляров
     * приложения. После получения блокировки проверяем реестр заново.
     * Исключение приводит к откату DDL и изменений в common_dictionaries.
     */
    @Transactional
    public void registerAll() {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(?)",
                Object.class,
                DICTIONARY_REGISTRATION_LOCK_KEY
        );

        List<DictionaryMetadataDto> all = scanner.scan();
        List<DictionaryMetadataDto> fresh = new ArrayList<>();
        List<CommonDictionary> modified = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        Set<String> entityNames = new HashSet<>();

        for (DictionaryMetadataDto metadata : all) {
            String code = metadata.code();
            String entityName = metadata.entityClass().getSimpleName();
            String dtoName = metadata.dtoClass().getName();
            if (code == null || !code.matches("[a-z_][a-z0-9_]{0,62}")) {
                throw new DictionaryRegistrationException("Недопустимый код справочника: " + code);
            }
            if (metadata.name() == null || metadata.name().isBlank()) {
                throw new DictionaryRegistrationException("Не указано название справочника: " + code);
            }
            if (!codes.add(code) || !entityNames.add(entityName)) {
                throw new DictionaryRegistrationException(
                        "Найдены повторяющиеся коды или простые имена Entity: " + code);
            }

            var byCode = repository.findByCode(code);
            var byEntity = repository.findByEntityClass(entityName);

            if (byCode.isEmpty() && byEntity.isEmpty()) {
                fresh.add(metadata);
                continue;
            }
            if (byCode.isEmpty() || byEntity.isEmpty()
                    || !byCode.get().getId().equals(byEntity.get().getId())) {
                throw new DictionaryRegistrationException(
                        "Нарушена неизменяемость кода или имени Entity для справочника '" + code
                                + "'. Проверьте запись в common_dictionaries.");
            }

            CommonDictionary saved = byCode.get();
            String schema = jdbc.queryForObject("select current_schema()", String.class);
            Boolean tableExists = jdbc.queryForObject(
                    "select to_regclass(format('%I.%I', ?, ?)) is not null",
                    Boolean.class, schema, code);
            if (!Boolean.TRUE.equals(tableExists)) {
                throw new DictionaryRegistrationException(
                        "Зарегистрированный справочник '%s' не имеет физической таблицы '%s.%s'."
                                .formatted(code, schema, code));
            }
            if (!metadata.name().equals(saved.getName()) || !dtoName.equals(saved.getDtoClass())) {
                saved.setName(metadata.name());
                saved.setDtoClass(dtoName);
                modified.add(saved);
            }
        }

        // Только после полного анализа разрешаем создание новых объектов.
        // Для уже зарегистрированных справочников DDL не выполняется.
        if (!fresh.isEmpty()) {
            generator.generateBatch(fresh);
            LocalDateTime generatedAt = LocalDateTime.now(ZoneOffset.UTC);
            for (DictionaryMetadataDto metadata : fresh) {
                CommonDictionary entity = new CommonDictionary();
                entity.setCode(metadata.code());
                entity.setName(metadata.name());
                entity.setEntityClass(metadata.entityClass().getSimpleName());
                entity.setDtoClass(metadata.dtoClass().getName());
                entity.setGeneratedAt(generatedAt);
                repository.save(entity);
            }
        }
        repository.saveAll(modified);
        log.info("Регистрация справочников: новых {}, обновлены метаданные {}.",
                fresh.size(), modified.size());
    }
}
