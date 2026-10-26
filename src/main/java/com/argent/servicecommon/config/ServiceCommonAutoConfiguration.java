package com.argent.servicecommon.config;

import com.argent.servicecommon.dictionary.generator.DictionarySchemaGenerator;
import com.argent.servicecommon.dictionary.generator.postgresql.PostgreSQLDictionarySchemaGenerator;
import com.argent.servicecommon.dictionary.service.DictionaryScannerService;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

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
}
