# service-common

Общая библиотека Spring Boot (4.1, Java 21) для бэкендов Argent. Подключается одной
зависимостью и через автоконфигурацию даёт:

| Блок | Что это |
|---|---|
| Аутентификация | вход по логину (и паролю), JWT RS256, сессии в БД, выход, регистрация, bootstrap-администратор |
| Права | роли хранятся в БД как JSON-гранты по таблицам и действиям, системная роль `ADMIN`, запрет по умолчанию |
| Администрирование | REST для пользователей, ролей и каталога подсказок для редактора грантов |
| Справочники | JPA-сущности с `@ArgentDictionary` получают таблицы и REST; доступ проверяется правилом по URL |
| Ошибки | единый формат `ProblemDetail` с `errors[{field, message}]` |
| Миграции | собственные таблицы библиотеки в отдельной истории Flyway `common_schema_history` |
| OpenAPI | springdoc, Swagger UI по `/swagger-ui.html` |

## Подключение

Сначала установить библиотеку в локальный Maven-репозиторий:

```bash
cd service-common
./mvnw install                 # с тестами (нужен Docker)
./mvnw install -DskipTests     # без тестов
```

Затем в `pom.xml` приложения:

```xml
<dependency>
    <groupId>com.argent</groupId>
    <artifactId>service-common</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Библиотека рассчитана на PostgreSQL. Spring MVC, Spring Security, OAuth2 Resource Server,
Flyway, JPA, Validation и springdoc приходят транзитивно.

## Обязательные настройки приложения

### Flyway — ломающее изменение

Миграции библиотеки (`classpath:db/migration/common`) больше **не** подключаются через
`spring.flyway.locations`. Библиотека применяет их сама, в отдельную таблицу истории
`common_schema_history`, и только потом запускает Flyway приложения.

```properties
# было
spring.flyway.locations=classpath:db/migration/common,classpath:db/migration/app
# стало
spring.flyway.locations=classpath:db/migration/app
```

- В `spring.flyway.locations` не должно быть `db/migration/common`. Рекомендуется указывать
  только `classpath:db/migration/app`, а не родительский каталог `classpath:db/migration`:
  Flyway может подхватить из подкаталогов миграции библиотеки в историю приложения.
- Источник данных, `spring.flyway.schemas` и `spring.flyway.default-schema` миграции
  библиотеки берут из настроек Flyway приложения.
- Baseline для Flyway приложения включает библиотека (`baselineOnMigrate=true`,
  `baselineVersion=0`): к моменту миграций приложения схема уже не пуста. Поэтому в истории
  приложения появляется запись baseline с версией 0, а миграции приложения начинаются с `V1`.
- **Существующая БД**, мигрированная по старой схеме (миграции библиотеки записаны в
  `flyway_schema_history` приложения), так не запустится. Пересоздайте схему или удалите из
  `flyway_schema_history` строки с версиями библиотеки.

Таблицы библиотеки: `common_dictionaries` (V1), `common_user`, `common_user_session` (V2),
`common_role` (с колонкой `grants jsonb`) и `common_user_role` (V3).

> Если приложение объявит свой бин `FlywayMigrationStrategy`, стратегия библиотеки
> отключится и её миграции **не выполнятся** — их придётся запускать самостоятельно
> (каталог `classpath:db/migration/common`, таблица `common_schema_history`).

### Пароль bootstrap-администратора

При запуске библиотека создаёт администратора `argent.auth.bootstrap-admin.login` (по
умолчанию `admin`) и назначает ему роль `ADMIN`. В парольном режиме (по умолчанию) пароль
обязателен: если администратора ещё нет, а `argent.auth.bootstrap-admin.password` пуст,
запуск прерывается с ошибкой `Задайте argent.auth.bootstrap-admin.password`.

```properties
argent.auth.bootstrap-admin.password=${ADMIN_PASSWORD}
```

Пароль существующего администратора при перезапуске не меняется. Если администратор уже
существует без пароля, а парольный режим включён, в журнал пишется WARN — пароль нужно
задать через `POST /api/users/{id}/password`.

### Ключ JWT

Токены подписываются RSA-ключом из `argent.auth.jwt.key-file`
(по умолчанию `./config/argent-jwt.pem`, путь относительно рабочего каталога процесса).

- Если файла нет, при первом запуске создаётся ключ RSA-2048 и сохраняется в PEM PKCS#8.
- На файловых системах с POSIX-правами файл создаётся с правами `rw-------`, недостающие
  каталоги — с `rwx------`. Если существующий файл читают группа или остальные пользователи,
  в журнал пишется WARN. На Windows действуют права по умолчанию.
- Если сохранить новый ключ не удалось, пишется WARN и ключ живёт только в памяти: после
  перезапуска все выданные токены станут недействительны.
- Закрытый ключ не должен попасть в репозиторий — добавьте в `.gitignore` приложения:

```gitignore
/config/argent-jwt.pem
```

Все экземпляры приложения должны использовать один и тот же файл ключа, иначе токен,
выданный одним экземпляром, не примет другой.

## Свойства

### `argent.auth.*`

| Свойство | По умолчанию | Описание |
|---|---|---|
| `argent.auth.password-required` | `true` | требуется ли пароль при входе; `false` — вход только по логину |
| `argent.auth.registration.enabled` | `false` | разрешена ли самостоятельная регистрация через `POST /api/auth/register` |
| `argent.auth.registration.default-roles` | пусто | коды ролей, назначаемых новому пользователю при регистрации; роли должны существовать, иначе регистрация отвечает 409 `Роль по умолчанию не найдена: <код>` |
| `argent.auth.bootstrap-admin.login` | `admin` | логин администратора, создаваемого при запуске |
| `argent.auth.bootstrap-admin.password` | пусто | его пароль; обязателен в парольном режиме, пока администратор не создан |
| `argent.auth.session-ttl` | `30d` | срок действия сессии и выданного для неё токена |
| `argent.auth.jwt.key-file` | `./config/argent-jwt.pem` | путь к закрытому RSA-ключу (PEM PKCS#8); создаётся, если файла нет |
| `argent.auth.roles-file` | `classpath:argent-roles.json` | ресурс со стартовыми ролями приложения (префиксы `classpath:`, `file:`); необязателен — если ресурса нет, роли не загружаются |
| `argent.auth.roles-file-mode` | `create` | режим загрузки ролей из файла: `create` или `overwrite` (см. «Стартовые роли») |

### `argent.security.*`

| Свойство | По умолчанию | Описание |
|---|---|---|
| `argent.security.enabled` | `true` | `false` отключает весь блок безопасности: JWT, цепочку фильтров, правило доступа к справочникам, вход, стартовые роли, администрирование |
| `argent.security.permit-paths` | пусто | дополнительные шаблоны путей, доступные без входа |
| `argent.security.cors.allowed-origins` | пусто | разрешённые источники CORS для `/api/**`; пустой список — CORS не настраивается |
| `argent.security.actions` | пусто | коды действий приложения для каталога подсказок `GET /api/grants/catalog`; действие `admin` добавляется всегда. На проверку прав не влияет |

Пути, открытые без входа всегда (дополняются `permit-paths`):
`/api/auth/login`, `/api/auth/register`, `/api/auth/config`, `/v3/api-docs/**`,
`/swagger-ui/**`, `/swagger-ui.html`, `/actuator/health`. Всё остальное требует токен
(справочники — ещё и грант, см. «Справочники»). HTTP-сессии не создаются, CSRF отключён.

При заданных `cors.allowed-origins` разрешены методы `GET, POST, PUT, PATCH, DELETE, OPTIONS`
и любые заголовки.

> `permit-paths` проверяются раньше правила справочников. Шаблон вроде `/api/**` или
> `/api/argent-dictionary/**` откроет справочники без входа и без грантов. Если метод с
> `@PreAuthorize` попадает под `permit-paths`, анонимный запрос к нему получит 403, а не 401.

### `argent.security.enabled=false`

Для контекстов, где нужны только справочники (например, интеграционные тесты справочников
или контекст без JPA-репозиториев пользователей и ролей), безопасность выключается:

```properties
argent.security.enabled=false
```

> **Внимание:** библиотека в этом режиме не создаёт цепочку фильтров, правило доступа к
> справочникам, бин `argentAccess`, вход, стартовые роли, bootstrap-администратора и REST
> администрирования. **Доступ к справочникам по грантам не проверяется.** Не используйте
> этот режим в рабочей среде.

Флаг предназначен для тестов и контекстов только со справочниками. `spring-boot-starter-security`
при этом остаётся в classpath, поэтому в веб-приложении срабатывает стандартная автоконфигурация
безопасности Spring Boot: цепочка по умолчанию требует входа на каждый запрос и включает
HTTP Basic и форму входа с паролем, сгенерированным и выведенным в журнал при запуске.
Веб-приложение, которому действительно не нужна безопасность, должно дополнительно исключить
из автоконфигурации
`org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration`
и `org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration`
или объявить собственный бин `SecurityFilterChain`.

## Вход без пароля

```properties
argent.auth.password-required=false
```

Вход выполняется только по логину (пароль в запросе не проверяется), при каждом запуске в
журнал пишется WARN `Вход без пароля включён (argent.auth.password-required=false)`. Пароль
bootstrap-администратора в этом режиме не обязателен. Режим предназначен для демо и стендов,
где пользователи импортируются из внешнего источника. `GET /api/auth/config` сообщает
клиенту `passwordRequired`, чтобы форма входа могла скрыть поле пароля.

## Модель доступа

- **Роль — JSON-документ с грантами**: `{code, name, description, system, grants}`. Роли
  хранятся в `common_role` и правятся через `/api/roles` без пересборки приложения.
- **Запрет по умолчанию**: у пользователя без ролей нет прав; роль только открывает доступ.
- **Эффективные права** пользователя — объединение грантов всех его ролей. Они читаются из БД
  при каждом запросе, поэтому изменение ролей и грантов действует сразу, без повторного входа.
- **`ADMIN` — системная роль**, создаётся при запуске (см. «Стартовые роли»).

### Формат грантов

```json
{
  "tables": { "dict_country": ["read"], "documents": ["read", "write"], "*": ["read"] },
  "actions": ["documents.approve"]
}
```

| Раздел | Ключи и значения |
|---|---|
| `tables` | ключ — имя таблицы `[a-z_][a-z0-9_]{0,62}` или `*` (любая таблица); значение — непустой массив из `read`, `write` (строчными) |
| `actions` | массив кодов действий `[a-z0-9_]+(\.[a-z0-9_-]+)*`, например `documents.approve` |

- **`write` не включает `read`**: чтобы и читать, и изменять, перечислите обе операции.
- Право на таблицу `t` и операцию `op` есть, если `tables["t"]` или `tables["*"]` содержит `op`.
- Встроенное действие **`admin`** даёт доступ к администрированию пользователей и ролей.
  Действие `admin` проходит любую проверку `@argentAccess.action(...)` и позволяет выдавать любые действия.
- `{}`, отсутствующие разделы и `null` — роль без прав.
- Разбор строгий. Ошибки возвращаются как 400 «Ошибка валидации» с `errors` (на каждое поле —
  первая найденная ошибка):

| Поле `errors[].field` | Сообщение | Когда |
|---|---|---|
| `grants` | `Неизвестное поле грантов: <ключ>` | ключ верхнего уровня, кроме `tables` и `actions` |
| `grants` | `Недопустимый формат грантов` | не JSON-объект |
| `grants.tables` | `Недопустимый формат грантов` | `tables` не объект или значение таблицы не массив |
| `grants.tables` | `Недопустимое имя таблицы: <имя>` | имя не `*` и не соответствует `[a-z_][a-z0-9_]{0,62}` |
| `grants.tables` | `Пустой набор операций для таблицы: <имя>` | `[]` |
| `grants.tables` | `Недопустимая операция: <значение>` | не `read` и не `write` |
| `grants.actions` | `Недопустимый формат грантов` | `actions` не массив |
| `grants.actions` | `Недопустимый код действия: <значение>` | не строка или не соответствует шаблону |

### Authorities

Конвертер токена превращает эффективные гранты в authorities Spring Security:

| Грант | Authority |
|---|---|
| `"documents": ["read", "write"]` | `table:documents:read`, `table:documents:write` |
| `"*": ["read"]` | `table:*:read` (ключ `*` сохраняется буквально) |
| `"actions": ["documents.approve"]` | `action:documents.approve` |

Роль, гранты которой в БД не разбираются, пропускается с ERROR в журнале — вход пользователю
это не ломает.

### Проверка в контроллерах

Бин `argentAccess` для SpEL учитывает `*`; проверять authorities через `hasAuthority` вручную
не нужно:

```java
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @GetMapping
    @PreAuthorize("@argentAccess.table('documents', 'read')")
    public List<DocumentDto> list() { ... }

    @PostMapping
    @PreAuthorize("@argentAccess.table('documents', 'write')")
    public DocumentDto create(@Valid @RequestBody DocumentDto dto) { ... }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@argentAccess.action('documents.approve')")
    public void approve(@PathVariable UUID id) { ... }
}
```

| Метод | Что проверяет |
|---|---|
| `@argentAccess.action(code)` | есть ли действие `code` (держателю `admin` — всегда да) |
| `@argentAccess.table(table, op)` | разрешена ли операция `op` (`read`/`write` без учёта регистра; иное — `false`) над таблицей с учётом `*` |

`@PreAuthorize` можно повесить и на класс. Метод без `@PreAuthorize` доступен **любому
вошедшему** пользователю — библиотека такие методы не ищет, следите за аннотациями сами.
Отказ — 403 «Недостаточно прав».

Коды действий приложения стоит перечислить в `argent.security.actions`, чтобы они появились в
подсказках редактора ролей:

```properties
argent.security.actions=documents.approve,documents.export
```

В сервисах те же проверки доступны статически через `CurrentUser`:

| Метод | Результат |
|---|---|
| `CurrentUser.id()` | `Optional<UUID>` вошедшего пользователя |
| `CurrentUser.sessionId()` | `Optional<UUID>` сессии из токена |
| `CurrentUser.authorities()` | `Set<String>` authorities (`table:…`, `action:…`) |
| `CurrentUser.canTable(table, op)` | как `@argentAccess.table` |
| `CurrentUser.hasAction(code)` | как `@argentAccess.action` |

Для анонимного запроса проверки возвращают `false`, а `id()` — пустое значение.

### Стартовые роли

**`ADMIN`** создаёт библиотека: `system=true`, гранты
`{"tables":{"*":["read","write"]},"actions":["admin"]}`. При каждом запуске роль выравнивается:
признак `system` и гранты приводятся к этому значению (имя и описание существующей роли не
меняются). Bootstrap-администратор получает `ADMIN`.

**Роли приложения** задаются файлом `argent.auth.roles-file` (по умолчанию
`classpath:argent-roles.json`, то есть `src/main/resources/argent-roles.json`). Файл
необязателен: если его нет, в журнал пишется INFO `Файл ролей не найден: …`.

```json
[
  {
    "code": "READER",
    "name": "Читатель",
    "description": "Видит все справочники",
    "grants": { "tables": { "*": ["read"] } }
  },
  {
    "code": "EDITOR",
    "name": "Редактор",
    "grants": { "tables": { "*": ["read", "write"] }, "actions": ["documents.approve"] }
  }
]
```

- Файл — JSON-массив объектов с полями `code` (обязательно, `[A-Z][A-Z0-9_]{0,49}`),
  `name` (обязательно, до 255 символов), `description` (необязательно, строка до 1000
  символов), `grants` (формат выше; без него — роль без прав).
- **Схема закрытая**: любое другое поле (как и ошибка JSON, кода, имени, грантов или повтор кода)
  **прерывает запуск** с `IllegalStateException` вида
  `Ошибка в файле ролей classpath:argent-roles.json, роль EDITOR: неизвестное поле grant`.
  Файл проверяется целиком до записи в БД; применяется одной транзакцией.
- Поле `system` допускается, но игнорируется (WARN). Роль `ADMIN` в файле игнорируется (WARN):
  её определяет библиотека.
- Роли, которых нет в файле, не трогаются ни в одном режиме; роли из файла не удаляются.

| `argent.auth.roles-file-mode` | Поведение |
|---|---|
| `create` (по умолчанию) | создаются только роли, которых ещё нет по `code`; существующие не меняются — правки администратора в интерфейсе переживают перезапуск |
| `overwrite` | дополнительно имя, описание и гранты существующих ролей приводятся к файлу (для стендов, где файл — источник истины) |

Файл ролей загружается раньше создания `ADMIN` и bootstrap-администратора. Роли из
`argent.auth.registration.default-roles` удобно описывать в этом же файле.

### Справочники: правило по URL

Доступ к справочникам проверяется в цепочке фильтров (`DictionaryAuthorizationManager`);
модуль справочников о правах не знает. Код справочника — имя его таблицы, он же ключ в
`grants.tables`.

| Запрос | Требуется |
|---|---|
| `GET /api/argent-dictionary` | любой вошедший; в списке все справочники, фильтрует клиент |
| `GET` и `HEAD` `/api/argent-dictionary/{code}/**` (`schema`, `form`, `records`, `options`) | `read` на таблицу `{code}` (или на `*`) |
| любой другой метод `/api/argent-dictionary/{code}/**` (`POST`, `PUT`, `DELETE`, а также `OPTIONS`, `PATCH`) | `write` на таблицу `{code}` (или на `*`) |

- `write` без `read` позволяет изменять записи, но не читать их.
- `options` — тоже чтение: формы со ссылочными полями работают, только если у пользователя
  есть `read` на связанные справочники. Выдавайте `read` на таблицы, на которые ссылаются.
- Без токена — 401, без гранта — 403.
- `permit-paths` шире этого правила: путь, открытый через `permit-paths`, доступен без проверки.
- Предварительные CORS-запросы (`OPTIONS`) при заданных `cors.allowed-origins` обрабатывает
  CORS-фильтр раньше этого правила; прочие `OPTIONS`-запросы считаются записью.

### Каталог подсказок

`GET /api/grants/catalog` (действие `admin`) отдаёт подсказки для редактора ролей:
`{tables, actions}`, где `tables` — коды всех зарегистрированных справочников, `actions` —
`argent.security.actions` плюс `admin`; оба списка по алфавиту без повторов. Каталог —
только подсказка: гранты можно выдавать и на таблицы приложения, которых в нём нет.

### Правила администрирования

Администрирование доступно держателю действия `admin` (`@argentAccess.action('admin')` на
контроллерах). Правила проверяются против эффективных прав **вызывающего** пользователя из
текущего запроса.

«Покрывается» означает: каждая операция над каждой таблицей разрешена вызывающему для этой
таблицы или через `*` (запрошенный `*` требует `*` у вызывающего), и каждое действие есть у
вызывающего. **Действие `admin` покрывает любые действия**: его держатель может выдавать
действия приложения, не имея их сам; таблицы при этом проверяются как обычно.

| Правило | Где | Ответ |
|---|---|---|
| **No-escalation**: создать роль можно, только если её гранты покрываются вашими | `PUT /api/roles/{code}` для новой роли | 403 `Нельзя выдать права, которых нет у вас` |
| **No-escalation**: изменить роль можно, только если покрываются **добавленные** гранты | `PUT /api/roles/{code}` для существующей роли | 403 то же |
| **No-escalation**: назначить роль можно, только если её гранты покрываются вашими (проверяются добавляемые роли) | `PUT /api/users/{id}/roles` | 403 `Нельзя выдать роль с правами, которых нет у вас: <код>` |
| **Доминирование**: управлять пользователем можно, только если его эффективные права покрываются вашими; над собой — всегда | `PUT /api/users/{id}`, `PUT /api/users/{id}/roles`, `POST /api/users/{id}/password`, `POST /api/users/{id}/revoke-sessions` | 403 `Нельзя управлять пользователем с большими правами` |
| Нельзя отключить самого себя | `PUT /api/users/{id}` | 409 `Нельзя отключить самого себя` |
| Нельзя снять с себя действие `admin` (сменой своих ролей) | `PUT /api/users/{id}/roles` | 409 `Нельзя снять с себя действие администратора` |
| **Последний админ**: нельзя оставить систему без активного пользователя с действием `admin` — отключением, снятием ролей или удалением `admin` из грантов роли | `PUT /api/users/{id}`, `PUT /api/users/{id}/roles`, `PUT /api/roles/{code}` | 409 `Нельзя оставить систему без активного администратора` |
| **Системная роль** не удаляется | `DELETE /api/roles/{code}` | 409 `Системную роль нельзя удалить` |
| **Системная роль** не сужается: её гранты при сохранении всегда приводятся к набору администратора (переданные гранты проверяются на формат и игнорируются), меняются только имя и описание | `PUT /api/roles/ADMIN` | 200 |
| Роль, назначенная пользователям, не удаляется | `DELETE /api/roles/{code}` | 409 `Роль назначена пользователям` |

Текст отказов по этим правилам передаётся клиенту в `detail`. Отказ `@PreAuthorize` (нет
действия `admin`) — 403 с общим `detail` «Недостаточно прав для выполнения операции.».

Смена пароля отзывает все сессии пользователя.

**Известные ограничения:**

- Изменение роли проверяет только добавленные гранты. Убирать гранты можно у любой роли, в том
  числе у назначенной пользователям с правами шире ваших: редактирование роли может сузить
  чужие права.
- Действие `admin` покрывает любые действия, включая само `admin`. Поэтому любой держатель
  `admin` может создать роль с `admin` (таблицы в ней — в пределах своих грантов) и назначить
  её тем, кем управляет.
- Вне аутентифицированного запроса (импорт, планировщик) у вызывающего нет прав: вызов
  `UserAdminService`/`RoleAdminService` из кода, выдающий или затрагивающий права, получит
  `GrantsAccessDeniedException` (см. «Импорт пользователей»).

## Справочники

JPA-сущность становится справочником с аннотацией `@ArgentDictionary`; код справочника —
имя таблицы из `@Table` (формат `[a-z_][a-z0-9_]{0,62}`). При запуске библиотека создаёт
таблицы новых справочников и регистрирует их в `common_dictionaries`.

```java
@Entity
@Table(name = "ref_city")
@ArgentDictionary(name = "Города", dtoClass = CityDto.class)
public class City { ... }

public record CityDto(
        Long id,
        @DictionaryField(label = "Название", order = 1) @NotBlank String name,
        @DictionaryField(label = "Код", readOnly = true) String code
) { }
```

`@DictionaryField` (на поле или компоненте record DTO): `label`, `description`, `order`,
`readOnly` (поле не переносится из DTO в сущность), `visible`. Ограничения значений — обычные
аннотации Jakarta Validation.

Доступ к справочнику `ref_city` выдаётся грантом `"ref_city": ["read"]` или
`"ref_city": ["read", "write"]` (см. «Справочники: правило по URL»).

### REST справочников

Все пути — под `/api/argent-dictionary`, требуют входа.

| Метод и путь | Грант | Что делает |
|---|---|---|
| `GET /api/argent-dictionary` | — | все зарегистрированные справочники |
| `GET /{code}/schema` | `read` | схема формы и выбора ссылок для фронтенда |
| `GET /{code}/form` | `read` | описание полей DTO и ограничений |
| `GET /{code}/records?page&size&sort&search&filter.<поле>` | `read` | страница записей (по умолчанию `page=0`, `size=20`) |
| `GET /{code}/records/{id}` | `read` | одна запись |
| `GET /{code}/options?page&size&search` | `read` | варианты выбора для полей-ссылок |
| `POST /{code}/records` | `write` | создать запись из полного DTO, 201 |
| `PUT /{code}/records/{id}` | `write` | полностью обновить запись |
| `DELETE /{code}/records/{id}` | `write` | удалить запись, 204 |

PATCH не поддерживается.

## Импорт пользователей

`UserProvisioningService.upsert(login, displayName)` создаёт активного пользователя без пароля
и ролей или обновляет отображаемое имя существующего. Роли, пароль и активность существующего
пользователя не меняются. Пробелы по краям логина отбрасываются.

`UserAdminService.setRoles` применяет правила администрирования к текущему пользователю
запроса. В импорте вне запроса (планировщик, `ApplicationRunner`) прав у вызывающего нет, и
назначение роли с грантами будет отклонено. Назначайте роли напрямую через репозитории —
правила администрирования при этом **не применяются**:

```java
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.service.UserProvisioningService;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;

@Service
@RequiredArgsConstructor
public class EmployeeImport {

    private final UserProvisioningService provisioning;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    @Transactional
    public void importEmployee(String login, String fullName) {
        User user = provisioning.upsert(login, fullName);
        Role reader = roleRepository.findByCode("READER").orElseThrow();
        UserRoleId link = new UserRoleId(user.getId(), reader.getId());
        if (!userRoleRepository.existsById(link)) {
            userRoleRepository.save(new UserRole(link));
        }
    }
}
```

Пользователь без пароля входит только в безпарольном режиме; в парольном пароль задаёт
администратор (`POST /api/users/{id}/password`).

## REST

Токен передаётся в заголовке `Authorization: Bearer <token>`.

### `/api/auth`

| Метод и путь | Доступ | Тело → ответ |
|---|---|---|
| `POST /api/auth/login` | открыт | `{login, password}` → `{token}`; 401 при неверных данных |
| `POST /api/auth/register` | открыт | `{login, displayName, password}` → 201 `{token}`; 403, если регистрация выключена; 400, если логин занят |
| `POST /api/auth/logout` | вошедший | → 204, сессия текущего токена отзывается |
| `GET /api/auth/me` | вошедший | → `{id, login, displayName, grants: {tables, actions}}` — эффективные права |
| `GET /api/auth/config` | открыт | → `{passwordRequired, registrationEnabled}` |

Пример `me`: `"grants": {"tables": {"*": ["read"], "documents": ["read", "write"]}, "actions": ["documents.approve"]}`
(таблицы, операции и действия — по алфавиту).

При регистрации: `login` до 100 символов, `displayName` до 255, `password` от 6 до 72.

### `/api/users` — требует действие `admin`

| Метод и путь | Тело → ответ |
|---|---|
| `GET /api/users?search&page=0&size=20` | → `DynamicPage<UserDto>` (`items, page, size, totalElements, totalPages, first, last`), по логину; `size` ограничивается 1..100; поиск по логину или имени без учёта регистра |
| `POST /api/users` | `{login, displayName, password?}` → 201 `UserDto`; пользователь создаётся без ролей, без `password` — без пароля; 400, если логин занят |
| `PUT /api/users/{id}` | `{displayName, enabled}` → `UserDto` |
| `PUT /api/users/{id}/roles` | `{roleCodes[]}` → `UserDto`; заменяет роли, пустой список снимает все; 400 `Неизвестная роль: <код>` |
| `POST /api/users/{id}/password` | `{password}` → 204; все сессии пользователя отзываются |
| `POST /api/users/{id}/revoke-sessions` | → 204 |

`UserDto`: `id, login, displayName, enabled, hasPassword, roles[], createdAt`.
Ограничения: `login` до 100 символов, `displayName` до 255, `password` от 6 до 72.

### `/api/roles` — требует действие `admin`

Роль адресуется кодом.

| Метод и путь | Тело → ответ |
|---|---|
| `GET /api/roles` | → список `RoleDto`, по коду |
| `GET /api/roles/{code}` | → `RoleDto`; 404, если роли нет |
| `PUT /api/roles/{code}` | `{name, description?, grants?}` → 200 `RoleDto`; создаёт роль, если её нет, иначе изменяет; гранты заменяются целиком (без `grants` — роль без прав) |
| `DELETE /api/roles/{code}` | → 204; только несистемная роль, не назначенная пользователям |

Код роли: `[A-Z][A-Z0-9_]{0,49}`, иначе 400 (`errors[].field` = `code`, `Недопустимый код роли`).
`name` обязательно, до 255 символов; `description` до 1000. `grants` — JSON-объект в формате
выше. `RoleDto`: `id, code, name, description, system, grants{tables, actions}`.

```http
PUT /api/roles/APPROVER
Content-Type: application/json

{
  "name": "Согласующий",
  "description": "Видит справочники, согласует документы",
  "grants": {
    "tables": { "documents": ["read", "write"], "*": ["read"] },
    "actions": ["documents.approve"]
  }
}
```

### `/api/grants` — требует действие `admin`

| Метод и путь | Ответ |
|---|---|
| `GET /api/grants/catalog` | `{tables[], actions[]}` — см. «Каталог подсказок» |

## Формат ошибок

Все ошибки возвращаются как `ProblemDetail` (`application/problem+json`). Ошибки валидации
дополнительно содержат `errors` — список пар `field`/`message`:

```json
{
  "type": "about:blank",
  "title": "Ошибка валидации",
  "status": 400,
  "detail": "Параметры запроса не прошли проверку.",
  "instance": "/api/users",
  "errors": [
    { "field": "login", "message": "не должно быть пустым" }
  ]
}
```

(текст `message` зависит от локали валидатора)

| Статус | `title` | Когда |
|---|---|---|
| 400 | Ошибка валидации | Bean Validation тела или параметров, `ValidationErrorsException` (есть `errors`), в том числе ошибки грантов |
| 400 | Некорректный запрос | неразбираемый JSON, неверный тип параметра, `IllegalArgumentException` |
| 401 | Требуется вход | нет токена или он недействителен; при неудачном входе `detail` — причина |
| 403 | Недостаточно прав | нет права; `detail` общий, кроме «Регистрация отключена» и отказов правил администрирования |
| 404 | Не найдено | `EntityNotFoundException` (пользователь, роль), `DynamicDictionaryNotFoundException` (справочник) |
| 409 | Конфликт данных | `IllegalStateException` (текст в `detail`), нарушение ограничения БД (общий текст) |
| 500 | Внутренняя ошибка сервера | всё прочее; подробности только в журнале |

Ошибки контроллеров справочников может формировать собственный обработчик модуля справочников
(`DynamicDictionaryExceptionHandler`) — формат и статусы те же, заголовок может отличаться
(например, «Справочник не найден» для 404).

Неизвестный код справочника (при наличии гранта на него или на `*`): маршруты
`/{code}/form`, `GET`/`PUT`/`DELETE /{code}/records/{id}`, `POST /{code}/records` отвечают
**400** (как и на отсутствующую запись), а маршруты `/{code}/schema`, `GET /{code}/records`,
`/{code}/options` — **404**. Без гранта на такой код — 403.

Ручная валидация в приложении — `throw new ValidationErrorsException(Map.of("поле", "сообщение"))`.
Текст SQL и стек вызовов клиенту не передаются.

## Замена частей библиотеки

Бины библиотеки объявлены с `@ConditionalOnMissingBean`: свой бин того же типа (или имени)
заменяет библиотечный (в том числе `argentAccess`, `DictionaryAuthorizationManager`,
`RoleSeeder`). Особого внимания требуют три случая.

> **`SecurityFilterChain`.** Библиотечная цепочка — бин `argentSecurityFilterChain`. Свой бин
> с этим именем полностью её заменяет вместе с открытыми путями, правилом справочников, CORS и
> ответами ProblemDetail. Дополнительная цепочка приложения должна иметь собственный
> `securityMatcher` и более высокий приоритет (`@Order`), иначе она конфликтует с библиотечной.

> **`JwtDecoder`.** Свой декодер отключает проверку библиотеки — существование и активность
> сессии и пользователя. Выход и отзыв сессий перестанут действовать до истечения токена.

> **`FlywayMigrationStrategy`.** Свой бин отключает миграции библиотеки (см. «Flyway»).

## Swagger UI

springdoc приходит транзитивно. Swagger UI — `/swagger-ui.html`, спецификация —
`/v3/api-docs`; оба пути открыты без входа. Для вызова защищённых методов получите токен
через `POST /api/auth/login`.

## Тесты

Интеграционные тесты используют Testcontainers (`postgres:17-alpine`) — нужен запущенный Docker.

```bash
./mvnw test
./mvnw -q install -DskipTests   # сборка и установка без тестов
```
