package com.argent.servicecommon.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

/**
 * Применяет миграции библиотеки в отдельную историю common_schema_history
 * перед миграциями приложения.
 */
@AutoConfiguration(before = FlywayAutoConfiguration.class)
public class CommonFlywayAutoConfiguration {

    /**
     * Включает baseline для Flyway приложения.
     *
     * Библиотека создаёт свои таблицы раньше Flyway приложения, поэтому схема
     * уже не пуста, а истории приложения ещё нет. Baseline с версией 0
     * позволяет приложению начать историю с версии 1.
     */
    @Bean
    public FlywayConfigurationCustomizer commonFlywayBaselineCustomizer() {
        return configuration -> configuration.baselineOnMigrate(true).baselineVersion("0");
    }

    /**
     * Сначала мигрирует таблицы библиотеки, затем миграции приложения.
     * Источник данных и схемы берутся из настроек Flyway приложения.
     * Если приложение определяет собственную стратегию, оно должно само
     * выполнить миграции библиотеки.
     */
    @Bean
    @ConditionalOnMissingBean(FlywayMigrationStrategy.class)
    public FlywayMigrationStrategy commonFlywayMigrationStrategy() {
        return appFlyway -> {
            Configuration appConfig = appFlyway.getConfiguration();
            Flyway.configure()
                    .dataSource(appConfig.getDataSource())
                    .schemas(appConfig.getSchemas())
                    .defaultSchema(appConfig.getDefaultSchema())
                    .locations("classpath:db/migration/common")
                    .table("common_schema_history")
                    .baselineOnMigrate(false)
                    .load()
                    .migrate();

            appFlyway.migrate();
        };
    }
}
