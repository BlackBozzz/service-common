package com.argent.servicecommon.security;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Общий PostgreSQL-контейнер для интеграционных тестов безопасности.
 *
 * <p>Контейнер запускается один раз на JVM и останавливается Ryuk после завершения тестов.</p>
 */
public final class SecurityTestContainer {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("argent_security_tests")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/security-test-init.sql");

    static {
        POSTGRES.start();
    }

    private SecurityTestContainer() {
    }

    /** Подключает Hibernate и Flyway к указанной схеме и задаёт тестовые настройки аутентификации. */
    public static void register(DynamicPropertyRegistry registry, String schema) {
        registry.add("spring.datasource.url", () -> {
            String jdbcUrl = POSTGRES.getJdbcUrl();
            return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        });
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/app");
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.jpa.properties.hibernate.type.prefer_native_enum_types", () -> "false");
        registry.add("argent.auth.bootstrap-admin.password", () -> "test-admin-pass");
        registry.add("argent.auth.jwt.key-file", () -> Path.of(
                System.getProperty("java.io.tmpdir"), "argent-test-" + UUID.randomUUID() + ".pem").toString());
    }
}
