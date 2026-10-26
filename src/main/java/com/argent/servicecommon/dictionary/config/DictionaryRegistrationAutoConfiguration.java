package com.argent.servicecommon.dictionary.config;

import com.argent.servicecommon.dictionary.generator.DictionarySchemaGenerator;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.dictionary.service.DictionaryRegistrationService;
import com.argent.servicecommon.dictionary.service.DictionaryScannerService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Подключает регистрацию справочников без изменений в основном приложении.
 *
 * При запуске Spring Boot регистрирует все справочники в одной транзакции
 * после инициализации JPA и применения миграций Flyway.
 */
@AutoConfiguration
public class DictionaryRegistrationAutoConfiguration {

    /** Создаёт сервис регистрации, если приложение не переопределило его. */
    @Bean
    @ConditionalOnMissingBean(DictionaryRegistrationService.class)
    public DictionaryRegistrationService dictionaryRegistrationService(
            DictionaryScannerService scanner,
            CommonDictionaryRepository repository,
            DictionarySchemaGenerator generator,
            DataSource dataSource) {
        return new DictionaryRegistrationService(
                scanner, repository, generator, new JdbcTemplate(dataSource));
    }

    /** Запускает регистрацию после готовности Spring Boot-контекста. */
    @Bean
    public ApplicationRunner argentDictionaryRegistrationRunner(
            DictionaryRegistrationService registrationService) {
        return args -> registrationService.registerAll();
    }
}
