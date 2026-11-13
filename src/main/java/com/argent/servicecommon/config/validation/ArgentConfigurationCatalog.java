package com.argent.servicecommon.config.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.argent.servicecommon.config.validation.ArgentPropertySpec.Kind.*;

/**
 * Каталог реальных ключей service-common и связанных стандартных свойств Spring.
 * Полные пути задаются явно, включая уровни bootstrap-admin, registration, jwt, cors.
 */
public final class ArgentConfigurationCatalog {
    private static final List<ArgentPropertySpec> DEFINITIONS = List.of(
            spec("argent.config-validation.enabled", "Проверка конфигурации при старте", BOOLEAN, "true", "true"),
            spec("argent.config-validation.unknown-keys", "Неизвестные ключи argent.*", ENUM, "warn", "warn", "ignore", "warn", "error"),
            spec("argent.config-validation.report-on-start", "Сводка проверки при запуске", BOOLEAN, "true", "true"),

            spec("argent.auth.password-required", "Обязательность пароля при входе", BOOLEAN, "true", "true"),
            spec("argent.auth.registration.enabled", "Самостоятельная регистрация", BOOLEAN, "false", "false"),
            spec("argent.auth.registration.default-roles", "Роли при самостоятельной регистрации", LIST, "READER,EDITOR", "пустой список"),
            spec("argent.auth.bootstrap-admin.login", "Логин первоначального администратора", NON_BLANK, "admin", "admin"),
            secret("argent.auth.bootstrap-admin.password", "Пароль первоначального администратора", "не задан"),
            spec("argent.auth.session-ttl", "Срок действия сессии", POSITIVE_DURATION, "30d", "30d"),
            spec("argent.auth.jwt.key-file", "Путь к закрытому JWT-ключу (не содержимое ключа)", PATH,
                    "./config/argent-jwt.pem", "./config/argent-jwt.pem"),
            spec("argent.auth.roles-file", "Файл начальных ролей", NON_BLANK, "classpath:argent-roles.json", "classpath:argent-roles.json"),
            spec("argent.auth.roles-file-mode", "Режим применения начальных ролей", ENUM, "CREATE", "CREATE", "CREATE", "OVERWRITE"),
            spec("argent.security.enabled", "Включить безопасность service-common", BOOLEAN, "true", "true"),
            spec("argent.security.permit-paths", "Пути, открытые без аутентификации", LIST, "/actuator/health,/api/auth/login", "пустой список"),
            spec("argent.security.cors.allowed-origins", "Разрешённые CORS-источники", LIST,
                    "https://app.example.org", "пустой список"),
            spec("argent.security.actions", "Дополнительные экшены в редакторе прав", LIST,
                    "orders.approve,docs.read", "пустой список"),

            spec("argent.queue.enabled", "Включить обработчик очередей", BOOLEAN, "true", "true"),
            spec("argent.queue.max-concurrent-queues", "Максимальное количество параллельных очередей", POSITIVE_INT, "4", "4"),
            spec("argent.queue.poll-interval-ms", "Интервал опроса очереди, мс", MIN_500_LONG, "2000", "2000"),
            spec("argent.queue.heartbeat-interval-ms", "Интервал heartbeat, мс", MIN_1000_LONG, "15000", "15000"),
            spec("argent.queue.lease-seconds", "Срок аренды задачи, секунды", MIN_30_INT, "60", "60"),

            spec("argent.mail.enabled", "Включить почтовую рассылку", BOOLEAN, "false", "false"),
            spec("argent.mail.from", "Адрес отправителя", EMAIL, "noreply@example.org", null),
            spec("argent.mail.from-name", "Отображаемое имя отправителя", TEXT, "AtomSkills", "не задано"),
            spec("argent.mail.reply-to", "Адрес для ответов", EMAIL, "support@example.org", "не задан"),
            spec("argent.mail.max-recipients-per-call", "Лимит получателей одного вызова", POSITIVE_INT, "5000", "5000"),
            spec("spring.mail.host", "SMTP-сервер", NON_BLANK, "smtp.example.org", null),
            spec("spring.mail.port", "SMTP-порт", PORT, "587", "25"),
            spec("spring.mail.username", "Имя пользователя SMTP", TEXT, "mailer@example.org", "не задан"),
            secret("spring.mail.password", "Пароль SMTP", "не задан"),
            spec("spring.mail.properties.mail.smtp.auth", "SMTP AUTH", BOOLEAN, "true", "false"),
            spec("spring.mail.properties.mail.smtp.starttls.enable", "SMTP STARTTLS", BOOLEAN, "true", "false"),
            spec("spring.mail.properties.mail.smtp.ssl.enable", "SMTP SSL", BOOLEAN, "false", "false"),

            spec("argent.attachments.enabled", "Включить вложения", BOOLEAN, "true", "true"),
            spec("argent.attachments.root", "Корень каталога вложений", PATH, "./data/attachments", "./data/attachments"),
            spec("argent.attachments.max-size-bytes", "Максимальный размер вложения, байты", POSITIVE_LONG, "20971520", "20971520"),
            spec("argent.attachments.allowed-types", "Разрешённые MIME-типы вложений", LIST, "image/png,application/pdf", "любой тип"),

            spec("argent.reports.preload", "Шаблоны JasperReports для предкомпиляции", LIST,
                    "classpath*:reports/*.jrxml", "пустой список"),
            spec("argent.reports.max-rows", "Максимальное число строк отчёта", POSITIVE_INT, "100000", "100000"),
            spec("argent.reports.query-timeout", "Таймаут SQL в отчёте", POSITIVE_DURATION, "60s", "60s"),
            spec("argent.reports.locale", "Языковой тег отчётов", LOCALE, "ru-RU", "ru-RU"),
            spec("argent.reports.default-font", "Шрифт отчётов", NON_BLANK, "DejaVu Sans", "DejaVu Sans"),
            spec("argent.reports.watch-files", "Обновление шаблонов при изменении файлов", BOOLEAN, "true", "true"),

            spec("argent.transfer.max-upload-bytes", "Лимит загружаемого файла импорта, байты", POSITIVE_LONG, "20971520", "20971520"),
            spec("argent.transfer.max-rows", "Лимит строк при импорте", POSITIVE_INT, "100000", "100000"),
            spec("argent.transfer.import-on-error", "Режим обработки ошибок импорта", ENUM, "fail-all", "fail-all", "fail-all", "skip-row"),
            spec("argent.transfer.export-max-columns", "Лимит столбцов экспорта", POSITIVE_INT, "1000", "1000"),
            spec("argent.transfer.export-max-cells", "Лимит ячеек экспорта", POSITIVE_LONG, "2000000", "2000000"),

            spec("argent.live.enabled", "Включить WebSocket-уведомления", BOOLEAN, "true", "true"),
            spec("argent.live.allowed-origins", "Разрешённые WebSocket-источники", LIST, "http://localhost:4200", "same-origin"),
            spec("argent.live.flush-ms", "Интервал объединения WebSocket-событий, мс", POSITIVE_LONG, "500", "500"),
            spec("argent.docs.enabled", "Включить раздел технической документации", BOOLEAN, "true", "true"),

            spec("spring.datasource.url", "JDBC-адрес базы данных", JDBC_URL, "jdbc:postgresql://localhost:5432/atomskills", null),
            spec("spring.datasource.username", "Пользователь базы данных", NON_BLANK, "atomskills", null),
            secret("spring.datasource.password", "Пароль базы данных", "не задан"),
            spec("spring.datasource.hikari.maximum-pool-size", "Размер пула подключений Hikari", POSITIVE_INT, "10", "10"),
            spec("spring.flyway.locations", "Каталоги миграций Flyway", LIST, "classpath:db/migration/app", "стандартное значение Spring Boot"),
            spec("spring.flyway.default-schema", "Схема миграций Flyway", TEXT, "public", "не задано"),
            spec("spring.jpa.hibernate.ddl-auto", "Режим управления DDL Hibernate", ENUM, "none", "определяется приложением", "none", "validate", "update", "create", "create-drop"),
            spec("spring.servlet.multipart.max-file-size", "Лимит загрузки HTTP-файла", DATA_SIZE, "20MB", "1MB"),
            spec("spring.servlet.multipart.max-request-size", "Лимит multipart-запроса", DATA_SIZE, "21MB", "10MB"),
            spec("server.port", "HTTP-порт приложения", PORT, "8080", "8080"),
            spec("springdoc.cache.disabled", "Отключение кэша спецификации Swagger", BOOLEAN, "true", "false")
    );

    private ArgentConfigurationCatalog() {}

    /** Полный каталог со всеми уровнями вложенности и примерами заполнения. */
    public static List<ArgentPropertySpec> all() {
        return DEFINITIONS;
    }

    public static Map<String, ArgentPropertySpec> byKey() {
        Map<String, ArgentPropertySpec> result = new LinkedHashMap<>();
        for (ArgentPropertySpec definition : DEFINITIONS) {
            if (result.putIfAbsent(definition.key(), definition) != null) {
                throw new IllegalStateException("Повтор описания свойства: " + definition.key());
            }
        }
        return Map.copyOf(result);
    }

    private static ArgentPropertySpec spec(String key, String label, ArgentPropertySpec.Kind kind,
                                            String example, String defaultValue, String... allowed) {
        return new ArgentPropertySpec(key, label, kind, example, defaultValue, false, List.of(allowed));
    }

    private static ArgentPropertySpec secret(String key, String label, String defaultValue) {
        return new ArgentPropertySpec(key, label, TEXT, "<секретное-значение>", defaultValue, true, List.of());
    }
}
