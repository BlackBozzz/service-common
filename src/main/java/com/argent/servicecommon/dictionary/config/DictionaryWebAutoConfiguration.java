package com.argent.servicecommon.dictionary.config;

import com.argent.servicecommon.dictionary.controller.ArgentDictionaryController;
import com.argent.servicecommon.dictionary.metadata.DictionaryMetadataService;
import com.argent.servicecommon.dictionary.service.DictionaryDtoCrudService;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Автоматическая конфигурация универсального REST API справочников.
 *
 * <p>Контроллер регистрируется автоматически при подключении
 * библиотеки service-common к Spring Boot-приложению.</p>
 */
@AutoConfiguration(after = DictionaryDtoAutoConfiguration.class)
public class DictionaryWebAutoConfiguration {

    /**
     * Создаёт универсальный REST-контроллер справочников.
     *
     * @param crudService сервис операций с DTO
     * @param metadataService сервис описания справочников
     * @param entityManager менеджер JPA-сущностей
     * @param jdbcTemplate средство выполнения SQL-запросов
     * @param jsonMapper средство преобразования JSON
     * @return универсальный контроллер
     */
    @Bean
    @ConditionalOnMissingBean(ArgentDictionaryController.class)
    public ArgentDictionaryController argentDictionaryController(
            DictionaryDtoCrudService crudService,
            DictionaryMetadataService metadataService,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate,
            JsonMapper jsonMapper
    ) {
        return new ArgentDictionaryController(
                crudService,
                metadataService,
                entityManager,
                jdbcTemplate,
                jsonMapper
        );
    }
}