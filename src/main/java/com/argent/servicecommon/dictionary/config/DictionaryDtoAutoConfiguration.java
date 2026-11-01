package com.argent.servicecommon.dictionary.config;

import com.argent.servicecommon.dictionary.mapper.DefaultDictionaryDtoMapper;
import com.argent.servicecommon.dictionary.mapper.DictionaryDtoMapper;
import com.argent.servicecommon.dictionary.metadata.DefaultDictionaryMetadataService;
import com.argent.servicecommon.dictionary.metadata.DictionaryMetadataService;
import com.argent.servicecommon.dictionary.service.DictionaryCrudService;
import com.argent.servicecommon.dictionary.service.DictionaryDtoCrudService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Подключает DTO-маппер и сервис метаданных как необязательную автоконфигурацию. */
@AutoConfiguration
public class DictionaryDtoAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DictionaryDtoMapper.class)
    public DictionaryDtoMapper dictionaryDtoMapper(EntityManager entityManager) {
        return new DefaultDictionaryDtoMapper(entityManager);
    }

    @Bean
    @ConditionalOnMissingBean(DictionaryMetadataService.class)
    public DictionaryMetadataService dictionaryMetadataService(
            EntityManager entityManager, JdbcTemplate jdbcTemplate,
            Validator validator, DictionaryDtoMapper mapper) {
        return new DefaultDictionaryMetadataService(entityManager, jdbcTemplate,
                validator, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(DictionaryDtoCrudService.class)
    public DictionaryDtoCrudService dictionaryDtoCrudService(
            DictionaryCrudService crud, DictionaryDtoMapper mapper, Validator validator) {
        return new DictionaryDtoCrudService(crud, mapper, validator);
    }
}
