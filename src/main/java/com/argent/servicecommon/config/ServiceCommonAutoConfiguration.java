package com.argent.servicecommon.config;

import com.argent.servicecommon.dictionary.generator.DictionarySchemaGenerator;
import com.argent.servicecommon.dictionary.generator.postgresql.PostgreSQLDictionarySchemaGenerator;
import com.argent.servicecommon.dictionary.repository.DictionaryRepository;
import com.argent.servicecommon.dictionary.repository.JpaDictionaryRepository;
import com.argent.servicecommon.dictionary.service.DictionaryCrudService;
import com.argent.servicecommon.dictionary.service.DictionaryScannerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@AutoConfiguration(
        beforeName = "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
)
@AutoConfigurationPackage(
        basePackages = "com.argent.servicecommon.dictionary"
)
public class ServiceCommonAutoConfiguration {
    /**
     * Регистрирует сервис обнаружения справочников.
     *
     * Позволяет основному приложению использовать стандартную
     * реализацию либо предоставить собственную.
     */
    @Bean
    @ConditionalOnMissingBean(DictionaryScannerService.class)
    public DictionaryScannerService dictionaryScannerService(
            EntityManagerFactory entityManagerFactory
    ) {
        return new DictionaryScannerService(entityManagerFactory);
    }

    /**
     * Регистрирует генератор таблиц справочников для PostgreSQL.
     *
     * Позволяет заменить стандартную реализацию генератора
     * собственной реализацией в основном приложении.
     */
    @Bean
    @ConditionalOnMissingBean(DictionarySchemaGenerator.class)
    public DictionarySchemaGenerator dictionarySchemaGenerator(
            DataSource dataSource,
            EntityManagerFactory entityManagerFactory
    ) {
        return new PostgreSQLDictionarySchemaGenerator(
                dataSource,
                entityManagerFactory
        );
    }

    @Bean
    @ConditionalOnMissingBean(DictionaryRepository.class)
    public DictionaryRepository dictionaryRepository(
            EntityManager entityManager
    ) {
        return new JpaDictionaryRepository(entityManager);
    }

    @Bean
    @ConditionalOnMissingBean(DictionaryCrudService.class)
    public DictionaryCrudService dictionaryCrudService(
            DictionaryRepository dictionaryRepository,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate
    ) {
        return new DictionaryCrudService(
                dictionaryRepository,
                entityManager,
                jdbcTemplate
        );
    }
}
