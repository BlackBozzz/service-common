# Проверка конфигурации AtomSkills — service-common

## Что реализовано

Библиотека проверяет **полные ключи настроек** при запуске, до создания обычных бинов Spring.
Сообщение содержит:

- Полный путь, включая вложенные свойства (`argent.auth.bootstrap-admin.password`).
- Русское название и причину ошибки.
- Допустимые значения, значение по умолчанию и примеры заполнения через `.properties`, YAML и переменную окружения.
- Для секретов — возможность чтения из Docker Secret (через Spring Boot `configtree:`).

**Пароли и значения секретов никогда не включаются в сообщение об ошибке или отчёт**.
Параметры с умолчаниями можно не задавать. Почта проверяется как обязательная **только при `argent.mail.enabled=true`**.

## Как включить

По умолчанию проверка включена. Настройка необязательна:

```properties
argent.config-validation.enabled=true
argent.config-validation.unknown-keys=warn
argent.config-validation.report-on-start=true
```

- `unknown-keys=warn`: незнакомые ключи встроенных модулей `argent.*` дают предупреждение и ближайшее известное имя, если оно найдено.
- `unknown-keys=error`: опечатки блокируют запуск. Выбирай этот режим только когда собственные настройки приложения не используют встроенные префиксы `argent.auth`, `argent.queue` и т. п.
- `unknown-keys=ignore`: не проверять неизвестные ключи.
- `enabled=false`: полностью отключить диагностику, **не отключая** штатную проверку Spring Boot и проверки внутри самих классов настроек.

Проверка **не соединяется с SMTP, PostgreSQL и не читает реальный JWT-ключ** — она проверяет формат и обязательность указанных настроек. Операционную доступность проверяют отдельные тесты/healthchecks.

## Пример ошибки с вложенным ключом

```text
[ERROR] argent.auth.session-ttl — Срок действия сессии
Причина: Указанное значение имеет неподходящий формат или выходит за допустимые пределы.
Допустимые значения: положительная длительность: 500ms, 15s, 30m, 2h, 30d либо ISO-8601 PT15M
По умолчанию: 30d
Как заполнить:
  application.properties: argent.auth.session-ttl=30d
  переменная окружения: ARGENT_AUTH_SESSION_TTL=30d
  application.yaml:
  argent:
    auth:
      session-ttl: 30d
```

В случае отсутствующего секрета SMTP имя будет `spring.mail.password`, а не просто `password`. Для пароля администратора — `argent.auth.bootstrap-admin.password`, а не `bootstrap-admin.password`.

## Примеры заполнения одних и тех же параметров

**application.properties:**

```properties
argent.auth.registration.enabled=false
argent.auth.session-ttl=30d
argent.queue.poll-interval-ms=2000
argent.security.cors.allowed-origins=http://localhost:4200
```

**application.yaml:**

```yaml
argent:
  auth:
    registration:
      enabled: false
    session-ttl: 30d
  queue:
    poll-interval-ms: 2000
  security:
    cors:
      allowed-origins:
        - http://localhost:4200
```

**Переменные окружения (в том числе Docker Compose `environment`):**

```env
ARGENT_AUTH_REGISTRATION_ENABLED=false
ARGENT_AUTH_SESSION_TTL=30d
ARGENT_QUEUE_POLL_INTERVAL_MS=2000
ARGENT_SECURITY_CORS_ALLOWED_ORIGINS=http://localhost:4200
```

**Docker Secrets / `configtree` (только приватные значения):**

```yaml
# application.yaml
spring:
  config:
    import: "optional:configtree:/run/secrets/"
```

Файл `/run/secrets/spring.datasource.password` содержит **сам пароль**, имя файла содержит полное имя свойства. Для `argent.auth.bootstrap-admin.password` создаётся файл с этим полным именем. Не добавляй приватные файлы в Git и не записывай пароль в `compose.yaml`.

Если у тебя в Docker Compose секрет называется `db_password`, он не станет автоматически свойством `spring.datasource.password`: нужно либо смонтировать его в контейнер **под правильным именем**, либо настроить отдельное сопоставление. Для обычных секретов с точным именем вложенного свойства достаточно `configtree`.

## Предупреждения и особенности

- `argent.queue.poll-interval-ms`, `argent.queue.heartbeat-interval-ms`, `argent.queue.lease-seconds`, `argent.queue.max-concurrent-queues` проверяются на минимумы. Ранее геттеры очереди могли молча подменять недопустимые значения.
- Включённая почта требует `spring.mail.host` и `argent.mail.from`; при `spring.mail.properties.mail.smtp.auth=true` — также `spring.mail.username` и `spring.mail.password`.
- Неизвестные ключи **только встроенных префиксов** обнаруживаются по перечислимым источникам Spring (properties, YAML, переменные окружения); динамические значения пользовательских модулей не проверяются.
- Для сложных списков/Map вложенных YAML-объектов корректность элементов после преобразования дополнительно проверяет Spring Binder.
- Пароль первоначального администратора **не обязателен** для уже инициализированной БД, поэтому не объявляется безусловно обязательным. При первой инициализации действуют прежние правила `service-common`.
- Нельзя считать проверку свойств полноценной проверкой безопасности или доступности внешних систем.

## Программный API

```java
// Массив проблем: ERROR и WARNING
List<ArgentConfigurationIssue> issues = ArgentConfigurationInspector.inspect(environment);

// Проверить, выбросить ArgentConfigurationException при ошибках
ArgentConfigurationInspector.validateOrThrow(environment);

// Отформатировать для лога/консоли без значения секретов
String report = ArgentConfigurationInspector.describeIssues(issues);

// Список допустимых ключей с полными именами и вариантами заполнения
for (ArgentPropertySpec property : ArgentConfigurationCatalog.all()) {
    System.out.println(property.key());
    System.out.println(property.fillingOptions());
}
```

## Каталог свойств

Ниже точные полные имена из предоставленных исходников и последних патчей. Список покрывает основные параметры `service-common` и связанные настройки Spring Boot, **но не все возможные свойства Spring и сторонних библиотек**. У приложения могут быть дополнительные собственные параметры.

| Полный ключ | Назначение | Пример | Значение по умолчанию |
|---|---|---|---|
| `argent.config-validation.enabled` | Проверка конфигурации при старте | `true` | true |
| `argent.config-validation.unknown-keys` | Неизвестные ключи argent.* | `warn` | warn |
| `argent.config-validation.report-on-start` | Сводка проверки при запуске | `true` | true |
| `argent.auth.password-required` | Обязательность пароля при входе | `true` | true |
| `argent.auth.registration.enabled` | Самостоятельная регистрация | `false` | false |
| `argent.auth.registration.default-roles` | Роли при самостоятельной регистрации | `READER,EDITOR` | пустой список |
| `argent.auth.bootstrap-admin.login` | Логин первоначального администратора | `admin` | admin |
| `argent.auth.bootstrap-admin.password` | Пароль первоначального администратора | `<секрет>` | не задан |
| `argent.auth.session-ttl` | Срок действия сессии | `30d` | 30d |
| `argent.auth.jwt.key-file` | Путь к закрытому JWT-ключу (не содержимое ключа) | `./config/argent-jwt.pem` | ./config/argent-jwt.pem |
| `argent.auth.roles-file` | Файл начальных ролей | `classpath:argent-roles.json` | classpath:argent-roles.json |
| `argent.auth.roles-file-mode` | Режим применения начальных ролей | `CREATE` | CREATE |
| `argent.security.enabled` | Включить безопасность service-common | `true` | true |
| `argent.security.permit-paths` | Пути, открытые без аутентификации | `/actuator/health,/api/auth/login` | пустой список |
| `argent.security.cors.allowed-origins` | Разрешённые CORS-источники | `https://app.example.org` | пустой список |
| `argent.security.actions` | Дополнительные экшены в редакторе прав | `orders.approve,docs.read` | пустой список |
| `argent.queue.enabled` | Включить обработчик очередей | `true` | true |
| `argent.queue.max-concurrent-queues` | Максимальное количество параллельных очередей | `4` | 4 |
| `argent.queue.poll-interval-ms` | Интервал опроса очереди, мс | `2000` | 2000 |
| `argent.queue.heartbeat-interval-ms` | Интервал heartbeat, мс | `15000` | 15000 |
| `argent.queue.lease-seconds` | Срок аренды задачи, секунды | `60` | 60 |
| `argent.mail.enabled` | Включить почтовую рассылку | `false` | false |
| `argent.mail.from` | Адрес отправителя | `noreply@example.org` | обязательно в соответствующем режиме |
| `argent.mail.from-name` | Отображаемое имя отправителя | `AtomSkills` | не задано |
| `argent.mail.reply-to` | Адрес для ответов | `support@example.org` | не задан |
| `argent.mail.max-recipients-per-call` | Лимит получателей одного вызова | `5000` | 5000 |
| `spring.mail.host` | SMTP-сервер | `smtp.example.org` | обязательно в соответствующем режиме |
| `spring.mail.port` | SMTP-порт | `587` | 25 |
| `spring.mail.username` | Имя пользователя SMTP | `mailer@example.org` | не задан |
| `spring.mail.password` | Пароль SMTP | `<секрет>` | не задан |
| `spring.mail.properties.mail.smtp.auth` | SMTP AUTH | `true` | false |
| `spring.mail.properties.mail.smtp.starttls.enable` | SMTP STARTTLS | `true` | false |
| `spring.mail.properties.mail.smtp.ssl.enable` | SMTP SSL | `false` | false |
| `argent.attachments.enabled` | Включить вложения | `true` | true |
| `argent.attachments.root` | Корень каталога вложений | `./data/attachments` | ./data/attachments |
| `argent.attachments.max-size-bytes` | Максимальный размер вложения, байты | `20971520` | 20971520 |
| `argent.attachments.allowed-types` | Разрешённые MIME-типы вложений | `image/png,application/pdf` | любой тип |
| `argent.reports.preload` | Шаблоны JasperReports для предкомпиляции | `classpath*:reports/*.jrxml` | пустой список |
| `argent.reports.max-rows` | Максимальное число строк отчёта | `100000` | 100000 |
| `argent.reports.query-timeout` | Таймаут SQL в отчёте | `60s` | 60s |
| `argent.reports.locale` | Языковой тег отчётов | `ru-RU` | ru-RU |
| `argent.reports.default-font` | Шрифт отчётов | `DejaVu Sans` | DejaVu Sans |
| `argent.reports.watch-files` | Обновление шаблонов при изменении файлов | `true` | true |
| `argent.transfer.max-upload-bytes` | Лимит загружаемого файла импорта, байты | `20971520` | 20971520 |
| `argent.transfer.max-rows` | Лимит строк при импорте | `100000` | 100000 |
| `argent.transfer.import-on-error` | Режим обработки ошибок импорта | `fail-all` | fail-all |
| `argent.transfer.export-max-columns` | Лимит столбцов экспорта | `1000` | 1000 |
| `argent.transfer.export-max-cells` | Лимит ячеек экспорта | `2000000` | 2000000 |
| `argent.live.enabled` | Включить WebSocket-уведомления | `true` | true |
| `argent.live.allowed-origins` | Разрешённые WebSocket-источники | `http://localhost:4200` | same-origin |
| `argent.live.flush-ms` | Интервал объединения WebSocket-событий, мс | `500` | 500 |
| `argent.docs.enabled` | Включить раздел технической документации | `true` | true |
| `spring.datasource.url` | JDBC-адрес базы данных | `jdbc:postgresql://localhost:5432/atomskills` | обязательно в соответствующем режиме |
| `spring.datasource.username` | Пользователь базы данных | `atomskills` | обязательно в соответствующем режиме |
| `spring.datasource.password` | Пароль базы данных | `<секрет>` | не задан |
| `spring.datasource.hikari.maximum-pool-size` | Размер пула подключений Hikari | `10` | 10 |
| `spring.flyway.locations` | Каталоги миграций Flyway | `classpath:db/migration/app` | стандартное значение Spring Boot |
| `spring.flyway.default-schema` | Схема миграций Flyway | `public` | не задано |
| `spring.jpa.hibernate.ddl-auto` | Режим управления DDL Hibernate | `none` | определяется приложением |
| `spring.servlet.multipart.max-file-size` | Лимит загрузки HTTP-файла | `20MB` | 1MB |
| `spring.servlet.multipart.max-request-size` | Лимит multipart-запроса | `21MB` | 10MB |
| `server.port` | HTTP-порт приложения | `8080` | 8080 |
| `springdoc.cache.disabled` | Отключение кэша спецификации Swagger | `true` | false |

## Установка и тесты

Распакуй патч в корень `AtomSkills`. Если файл `AutoConfiguration.imports` уже менялся локально, не заменяй целиком: добавь только строку `com.argent.servicecommon.config.validation.ArgentConfigurationValidationAutoConfiguration`.

```powershell
cd D:\Projects\AtomSkills\service-common
.\mvnw.cmd clean install
.\mvnw.cmd test -DskipTests=false -Dtest=ArgentConfigurationInspectorTest
```

Тесты по-прежнему пропускаются при обычном `install` согласно существующей настройке проекта.
