package com.argent.servicecommon.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что библиотека применяет свои миграции в отдельную историю
 * common_schema_history, не смешивая её с историей приложения.
 */
@SpringBootTest(
        classes = CommonFlywayIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Testcontainers
class CommonFlywayIntegrationTest {

    private static final String SCHEMA = "it_flyway";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("argent_common_flyway_tests")
            .withUsername("test")
            .withPassword("test");

    /** Подключаем Hibernate и Flyway к одной схеме; миграции приложения отсутствуют. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String jdbcUrl = postgres.getJdbcUrl();
            return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        });
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/app");
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Ключ JWT создаётся при запуске; не оставляем его в рабочем каталоге модуля.
        registry.add("argent.auth.jwt.key-file", () -> keyDirectory.resolve("argent-jwt.pem").toString());
        registry.add("argent.attachments.root", () -> keyDirectory.resolve("attachments").toString());
        registry.add("argent.auth.bootstrap-admin.password", () -> "test-admin-pass");
    }

    @TempDir
    static Path keyDirectory;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired private JdbcTemplate jdbc;

    @Test
    void shouldApplyLibraryMigrationsInOwnHistory() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, SCHEMA);
        assertThat(tables).contains(
                "common_dictionaries", "common_user", "common_user_session", "common_role",
                "common_user_role", "common_schema_history", "flyway_schema_history",
                "common_status_type", "common_status", "common_status_transition",
                "common_sequence", "common_audit_entry", "common_attachment");
        assertThat(tables).doesNotContain("common_permission", "common_role_permission");

        List<String> libraryVersions = jdbc.queryForList(
                "SELECT version FROM " + SCHEMA + ".common_schema_history WHERE version IS NOT NULL",
                String.class);
        assertThat(libraryVersions).containsExactlyInAnyOrder("1", "2", "3", "4", "5");

        List<String> appVersions = jdbc.queryForList(
                "SELECT version FROM " + SCHEMA + ".flyway_schema_history WHERE version IS NOT NULL",
                String.class);
        assertThat(appVersions).doesNotContain("1");

        List<String> baselineRows = jdbc.queryForList(
                "SELECT version FROM " + SCHEMA + ".flyway_schema_history WHERE type = 'BASELINE'",
                String.class);
        assertThat(baselineRows).containsExactly("0");
    }

    @Test
    void shouldUseUuidPrimaryKeys() {
        for (String table : List.of("common_user", "common_role", "common_status_type", "common_status",
                "common_status_transition", "common_sequence", "common_audit_entry",
                "common_attachment")) {
            String type = jdbc.queryForObject(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = ? AND table_name = ? AND column_name = 'id'",
                    String.class, SCHEMA, table);
            assertThat(type).as(table + ".id").isEqualTo("uuid");
        }
    }

    @Test
    void shouldStoreRoleGrantsAsJsonb() {
        String type = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'common_role' AND column_name = 'grants'",
                String.class, SCHEMA);
        assertThat(type).isEqualTo("jsonb");
    }

    @Test
    void shouldRegisterLibraryDictionariesInMigration() {
        // В контекст этого теста попадают и тестовые справочники других пакетов — проверяем только библиотечные.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT code, entity_class, dto_class FROM common_dictionaries WHERE code IN (?, ?, ?, ?)",
                "common_status_type", "common_status", "common_status_transition", "common_sequence");
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(row -> row.get("entity_class")).containsExactlyInAnyOrder(
                "CommonStatusType", "CommonStatus", "CommonStatusTransition", "CommonSequence");
        assertThat(rows).extracting(row -> row.get("dto_class")).containsExactlyInAnyOrder(
                "com.argent.servicecommon.status.dto.CommonStatusTypeDto",
                "com.argent.servicecommon.status.dto.CommonStatusDto",
                "com.argent.servicecommon.status.dto.CommonStatusTransitionDto",
                "com.argent.servicecommon.sequence.dto.CommonSequenceDto");
    }

    @Test
    void shouldIndexAuditEntriesByEntityAndTime() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = 'common_audit_entry'",
                String.class, SCHEMA);
        assertThat(indexes).anyMatch(def -> def.contains("(entity_type, entity_id, created_at)"));
    }
}
