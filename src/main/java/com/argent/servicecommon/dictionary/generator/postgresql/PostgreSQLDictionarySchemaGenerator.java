package com.argent.servicecommon.dictionary.generator.postgresql;

import com.argent.servicecommon.dictionary.dto.DictionaryMetadataDto;
import com.argent.servicecommon.dictionary.exception.DictionaryRegistrationException;
import com.argent.servicecommon.dictionary.generator.DictionarySchemaGenerator;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * Выполняет DDL новых справочников в транзакции регистрации.
 *
 * SQL строится отдельным компонентом через Hibernate, поэтому здесь нет
 * собственного сопоставления Java-типов, аннотаций и SQL-конструкций.
 */
@Slf4j
public class PostgreSQLDictionarySchemaGenerator implements DictionarySchemaGenerator {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final HibernateDictionaryDdlBuilder ddlBuilder;

    public PostgreSQLDictionarySchemaGenerator(DataSource dataSource,
                                                EntityManagerFactory entityManagerFactory) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.ddlBuilder = new HibernateDictionaryDdlBuilder(entityManagerFactory, jdbc);
    }

    /**
     * Генерирует структуру всех новых справочников разом.
     *
     * Требуется существующая Spring-транзакция. JdbcTemplate использует
     * привязанное к ней соединение; отдельного commit() здесь нет.
     */
    @Override
    public void generateBatch(List<DictionaryMetadataDto> newDictionaries) {
        if (newDictionaries.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new DictionaryRegistrationException(
                    "Генерацию справочников необходимо запускать в транзакции Spring.");
        }
        // Определяем схему тем же JdbcTemplate, который выполнит DDL.
        String schema = jdbc.execute((ConnectionCallback<String>) connection -> connection.getSchema());
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new DictionaryRegistrationException("Недопустимая схема PostgreSQL: " + schema);
        }

        List<String> commands = ddlBuilder.build(newDictionaries, schema);
        try {
            for (String sql : commands) {
                log.debug("Выполнение команды создания справочника: {}", sql);
                jdbc.execute(sql);
            }
        } catch (DataAccessException e) {
            throw new DictionaryRegistrationException(
                    "PostgreSQL не смог создать структуру справочников: " + e.getMessage());
        }
        log.info("Сформирована структура {} новых справочников, выполнено {} DDL-команд.",
                newDictionaries.size(), commands.size());
    }
}
