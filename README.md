# service-common

Библиотека Spring Boot 4.1 для бэкендов Argent: вход по JWT, роли-гранты, админка пользователей,
справочники из JPA-сущностей, аудит, вложения, статусы, нумерация, импорт и экспорт. Подключается
одной зависимостью, всё включается автоконфигурацией. Полное описание — [docs/reference.md](docs/reference.md).

Нужно: Java 21, PostgreSQL 15+ (тесты — 17), Maven; Docker — только для тестов библиотеки.

## Шаг 1. Зависимость

Сначала jar библиотеки в `~/.m2`: `cd service-common && ./mvnw -q install -DskipTests`.

Минимальный `pom.xml` приложения целиком (плюс класс `@SpringBootApplication` с `main`):

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent><groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId><version>4.1.2</version></parent>
    <groupId>com.example</groupId><artifactId>backend</artifactId><version>0.0.1</version>
    <properties><java.version>21</java.version></properties>
    <dependencies>
        <dependency><groupId>com.argent</groupId><artifactId>service-common</artifactId>
            <version>0.0.1-SNAPSHOT</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId>
            <scope>runtime</scope></dependency>
    </dependencies>
    <build><plugins><plugin><groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

Security, JPA, Flyway, Validation, springdoc, POI и commons-csv приходят транзитивно; Lombok — нет.

Проверка: `mvn -q compile` (или `./mvnw`) завершается без ошибок.

## Шаг 2. `application.properties`

Блок копируется целиком; замените схему `app` и учётные данные БД на свои.

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/postgres?currentSchema=app
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.jpa.open-in-view=false
spring.jpa.hibernate.ddl-auto=none
spring.jpa.properties.hibernate.default_schema=app
# Миграции библиотеки (db/migration/common) она применяет сама; сюда — только свои
spring.flyway.locations=classpath:db/migration/app
spring.flyway.default-schema=app
spring.flyway.schemas=app
# Пароль admin из переменной окружения; двоеточие обязательно
argent.auth.bootstrap-admin.password=${ARGENT_ADMIN_PASSWORD:}
# Лимит Spring по умолчанию 1 МБ — поднять до лимитов импорта и вложений (20 МБ)
spring.servlet.multipart.max-file-size=20MB
spring.servlet.multipart.max-request-size=21MB
# Папка файлов вложений (создаётся при запуске)
argent.attachments.root=./data/attachments
```

В `.gitignore` приложения: `/config/argent-jwt.pem` (ключ JWT создаётся при первом запуске) и `/data/`.

```bash
ARGENT_ADMIN_PASSWORD=secret123 mvn spring-boot:run
```

Без пароля запуск прерывается с `Задайте argent.auth.bootstrap-admin.password`. Норма при запуске —
WARN: Flyway `No migrations found` (нет своих миграций), Hibernate `HHH90000025: PostgreSQLDialect does not
need to be specified` (от справочников библиотеки) и два WARN SpringDoc о включённых эндпоинтах документации.
Другой порт — `server.port=9080` (и `target` в `proxy.conf.json` фронтенда); «Port 8080 was already in use» при пустом
netstat — диапазон Hyper-V (`netsh int ipv4 show excludedportrange protocol=tcp`). Подробно: [Подключение и свойства](docs/reference.md#1-подключение-и-свойства), [Вход и сессии](docs/reference.md#2-вход-и-сессии).

Проверка: в логе `Создан администратор admin`; `curl localhost:8080/api/auth/config` →
`{"passwordRequired":true,"registrationEnabled":false}`.

## Шаг 3. Роли и пользователи

Права — гранты: `tables` (`read`/`write` по имени таблицы или `*`; `write` не включает `read`) и `actions`. Роль
`ADMIN` создаёт библиотека, свои — файл `src/main/resources/argent-roles.json` (создаются при запуске, если их нет):

```json
[ { "code": "MANAGER", "name": "Менеджер",
    "grants": { "tables": { "*": ["read"], "orders": ["read", "write"] },
                "actions": ["orders.approve", "import"] } },
  { "code": "VIEWER", "name": "Наблюдатель", "grants": { "tables": { "*": ["read"] } } } ]
```

```properties
# Коды действий для подсказок редактора ролей (на проверку прав не влияют)
argent.security.actions=orders.approve,import
```

Ошибка в файле прерывает запуск. Пользователи заводятся под `admin`: `POST /api/users`
(`{login, displayName, password}`; пароль 6–72 символа или позже `POST /api/users/{id}/password`), затем
`PUT /api/users/{id}/roles` (пример ниже). Правила — [Гранты и роли](docs/reference.md#3-гранты-и-роли).

```bash
H=localhost:8080   # другой порт — правка только здесь
TOKEN=$(curl -s $H/api/auth/login -H 'Content-Type: application/json' \
  -d '{"login":"admin","password":"secret123"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s $H/api/auth/me -H "Authorization: Bearer $TOKEN"
# id нового пользователя — поле $.id ответа POST /api/users
ID=$(curl -s $H/api/users -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"login":"ivanov","displayName":"Ivanov","password":"secret123"}' | sed 's/^{"id":"\([^"]*\)".*/\1/')
curl -s -X PUT $H/api/users/$ID/roles -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"roleCodes":["MANAGER"]}'
```

Проверка: в логе `Создана роль MANAGER из файла ролей classpath:argent-roles.json`; `/api/auth/me` →
`{"id":…,"login":"admin",…,"grants":{"tables":{"*":["read","write"]},"actions":["admin"]}}`.

## Шаг 4. Своя сущность

Сущность с `@ArgentDictionary` получает таблицу (код справочника = имя таблицы), REST и форму в
админке. Таблицу создаёт библиотека при запуске. `Order.java` и `OrderDto.java` — отдельные файлы:

```java
// Order.java
import com.argent.servicecommon.audit.Audited;
import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Audited @Table(name = "orders")
@ArgentDictionary(name = "Заказы", dtoClass = OrderDto.class)
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String number;                  // номер заказа — бизнес-поле
    @Column(nullable = false) private String customer;
    private BigDecimal total;
    @Version private Long version;          // оптимистическая блокировка
}

// OrderDto.java
import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record OrderDto(                     // order — порядок колонок, задавайте всем полям
        @DictionaryField(label = "ID", readOnly = true, order = 1) Long id,
        @DictionaryField(label = "Номер", order = 2) String number,
        @DictionaryField(label = "Заказчик", order = 3) @NotBlank String customer,
        @DictionaryField(label = "Сумма", order = 4) BigDecimal total,
        @DictionaryField(label = "Версия", readOnly = true, order = 5) Long version) { }
```

`@Audited` — библиотечный, не `org.hibernate.envers.Audited`. Имена компонентов DTO = имена полей сущности;
геттеры и сеттеры библиотеке не нужны (своему коду добавьте сами).

Доступ к `/api/argent-dictionary/orders/**`: `GET` — `read`, остальное — `write`. Свои контроллеры: `@PreAuthorize(
"@argentAccess.table('orders', 'write')")` или `@argentAccess.action('orders.approve')` (без него — любому вошедшему).

`readOnly`-версию REST справочника не сверяет — это делает свой код (`DemoOrderService.changeStatus` в
`main-backend`); устаревший `@Version` при сохранении — 409. [Справочники](docs/reference.md#4-справочники).

Проверка: `GET /api/argent-dictionary` содержит `orders`; после `POST /api/argent-dictionary/orders/records` с `{"number":"2026-001","customer":"ООО Ромашка","total":100}` (201)
`GET /api/argent-dictionary/orders/records` → `{"items":[{"id":1,…}],"page":0,…,"totalElements":1,…}`;
в админке записи — на странице `/admin/dictionaries/<код>` (здесь `/admin/dictionaries/orders`).
Кириллица в `curl -d` из Git Bash на Windows уходит не в UTF-8 (в БД `���`): кладите JSON в файл, `--data-binary @order.json`.

## Шаг 5. Аудит

`@Audited` на сущности (шаг 4) пишет `INSERT`/`UPDATE` (по полю)/`DELETE` в `common_audit_entry`;
`@AuditIgnore` на поле заменяет значения на `***`. Читать журнал записи:

```bash
curl -s "$H/api/audit?entityType=orders&entityId=1" -H "Authorization: Bearer $TOKEN"
```

Нужен `read` на таблицу или действие `admin`; без `entityType` — только `admin` (остальным 400 «Укажите
entityType»). Изменения вне Spring-транзакции и через свой `EntityManagerFactory` — [Аудит](docs/reference.md#9-аудит).

Проверка: ответ содержит `"action":"INSERT"` с `"actorLogin":"admin"` для созданного заказа.

## Шаг 6. По необходимости

### Вложения

Файлы к записи любой таблицы; права таблицы-владельца: список и скачивание — `read` (без него 404), загрузка
и удаление — `write`. HTML, SVG, `*+xml`, JavaScript и исполняемые файлы не принимаются; тип определяется
по сигнатуре (PDF/PNG/JPEG/GIF/ZIP/EXE/ELF) или по расширению.

```bash
curl -s $H/api/attachments -H "Authorization: Bearer $TOKEN" \
  -F entityType=orders -F entityId=1 -F file=@contract.pdf
```

Удаляя запись своим кодом, удаляйте и файлы: `attachmentService.deleteAll("orders", id.toString())` в той же
транзакции (REST справочника оставляет файлы-сироты). Лимиты и типы: [Вложения](docs/reference.md#10-вложения).

Проверка: 201 с `{"id":…,"fileName":"contract.pdf","contentType":"application/pdf",…}`;
в `/api/audit?entityType=orders` появилась строка `"action":"ATTACH"`.

### Статусы

Типы, статусы и переходы — библиотечные справочники `common_status_type`, `common_status`,
`common_status_transition` (заполняются в админке или миграцией, пример SQL — в
[Статусы](docs/reference.md#7-статусы)). Поле статуса хранит сама сущность, библиотека только проверяет переход:

```java
statuses.assertAllowed("ORDER_STATUS", order.getStatus(), "DONE");  // null — создание; 404/409/403
order.setStatus("DONE");
```

Проверка: после миграции из [Статусы](docs/reference.md#7-статусы) `GET /api/status/ORDER_STATUS/transitions`
→ статусы, доступные при создании (`NEW`).

### Нумерация

Счётчик — строка `common_sequence` (админка или миграция):

```sql
INSERT INTO common_sequence (id, code, pattern, reset_period, counter)
VALUES (gen_random_uuid(), 'ORDER', 'ORD-%1$s-%2$05d', 'YEARLY', 0) ON CONFLICT (code) DO NOTHING;
```

`numberGenerator.next("ORDER")` в транзакции создания записи. Подробно: [Нумерация](docs/reference.md#8-нумерация).

Проверка: первый вызов возвращает `ORD-<год>-00001`.

### Импорт и экспорт

```bash
curl -s $H/api/import/dictionaries/orders -H "Authorization: Bearer $TOKEN" \
  -F file=@orders.xlsx -F mode=UPSERT -F keyFields=customer
curl -s "$H/api/export/dictionaries/orders?format=xlsx" -H "Authorization: Bearer $TOKEN" -o orders.xlsx
```

Импорт — действие `import` и `write` на таблицу; экспорт — `read`. Ошибочные строки —
`argent.transfer.import-on-error` (`fail-all` или `skip-row`). Подробно: [Импорт](docs/reference.md#5-импорт), [Экспорт](docs/reference.md#6-экспорт).

Проверка: импорт отвечает `{"total":…,"created":…,"updated":…,"failed":0,"errors":[]}`.

## Демо-режим без пароля

`argent.auth.password-required=false`: вход только по логину, пароль администратора не нужен.
**Любой, кто достучится до бэкенда, войдёт под любым пользователем, включая `admin`. Только для демо.**

Проверка: WARN `Вход без пароля включён (…)`; `/api/auth/config` → `"passwordRequired":false`.

`argent.security.enabled=false` — другое: выключает всю безопасность ([подробно](docs/reference.md#argentsecurityenabledfalse)).

## Ссылки

- [docs/reference.md](docs/reference.md) — свойства, REST с кодами, сообщения, лимиты, замена бинов, миграция.
- Swagger UI — `http://localhost:8080/swagger-ui.html` (открыт без входа; токен — `POST /api/auth/login`).
- Пример приложения — `main-backend` (заказы со статусами, номерами, `@Version` и вложениями).
- Тесты библиотеки: `./mvnw test` (нужен Docker).
