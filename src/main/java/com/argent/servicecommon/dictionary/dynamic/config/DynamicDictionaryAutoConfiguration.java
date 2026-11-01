package com.argent.servicecommon.dictionary.dynamic.config;

import com.argent.servicecommon.dictionary.config.DictionaryDtoAutoConfiguration;
import com.argent.servicecommon.dictionary.dynamic.controller.DynamicDictionaryController;
import com.argent.servicecommon.dictionary.dynamic.controller.DynamicDictionaryExceptionHandler;
import com.argent.servicecommon.dictionary.dynamic.metadata.*;
import com.argent.servicecommon.dictionary.dynamic.service.*;
import com.argent.servicecommon.dictionary.mapper.DictionaryDtoMapper;
import com.argent.servicecommon.dictionary.metadata.DictionaryMetadataService;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Обязательная автоконфигурация динамических форм и таблиц.
 * Подключается вместе с библиотекой и не заменяет текущий REST-контроллер.
 */
@AutoConfiguration(after = DictionaryDtoAutoConfiguration.class,
        afterName = "com.argent.servicecommon.dictionary.config.DictionaryWebAutoConfiguration")
public class DynamicDictionaryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DynamicDictionaryCatalog.class)
    public DynamicDictionaryCatalog dynamicDictionaryCatalog(JdbcTemplate jdbcTemplate,
            EntityManager entityManager, DictionaryMetadataService metadataService) {
        return new DefaultDynamicDictionaryCatalog(jdbcTemplate, entityManager, metadataService);
    }

    @Bean
    @ConditionalOnMissingBean(DynamicDictionarySchemaService.class)
    public DynamicDictionarySchemaService dynamicDictionarySchemaService(
            DynamicDictionaryCatalog catalog, EntityManager entityManager) {
        return new DefaultDynamicDictionarySchemaService(catalog, entityManager);
    }

    @Bean
    @ConditionalOnMissingBean(DynamicDictionaryQueryService.class)
    public DynamicDictionaryQueryService dynamicDictionaryQueryService(
            DynamicDictionaryCatalog catalog, DynamicDictionarySchemaService schema,
            EntityManager entityManager, DictionaryDtoMapper mapper) {
        return new DefaultDynamicDictionaryQueryService(catalog, schema, entityManager, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(DynamicDictionaryController.class)
    public DynamicDictionaryController dynamicDictionaryController(
            DynamicDictionaryCatalog catalog, DynamicDictionarySchemaService schema,
            DynamicDictionaryQueryService queries) {
        return new DynamicDictionaryController(catalog, schema, queries);
    }

    @Bean
    @ConditionalOnMissingBean(DynamicDictionaryExceptionHandler.class)
    public DynamicDictionaryExceptionHandler dynamicDictionaryExceptionHandler() {
        return new DynamicDictionaryExceptionHandler();
    }
}
