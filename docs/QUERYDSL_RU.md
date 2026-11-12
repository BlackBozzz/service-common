# Argent QueryDSL — service-common

Java 21 / Spring Boot 4.1.2 / Jakarta Persistence / OpenFeign QueryDSL 7.7.

## 1. Подключение

В библиотеке `service-common` добавлена зависимость `io.github.openfeign.querydsl:querydsl-jpa:7.7` и аннотационный процессор `io.github.openfeign.querydsl:querydsl-apt:7.7:jpa`. Компиляция генерирует Q-классы в `target/generated-sources/java` (они попадают в JAR библиотеки).

Автоконфигурация `ArgentQuerydslAutoConfiguration` регистрирует `JPAQueryFactory`, если приложение не определило свою. Поэтому можно просто внедрить `JPAQueryFactory` в сервис:

```java
@RequiredArgsConstructor
@Service
public class ExampleQueryService {
    private final JPAQueryFactory queryFactory;
}
```

**Важно:** обработчик APT из POM библиотеки не наследуется POM приложений через Maven dependency. Если в `main-backend` или новом проекте есть *свои* JPA-сущности, в его `pom.xml` также нужно настроить `querydsl-apt` (см. § 6). Q-классы для сущностей самой библиотеки уже доступны в JAR `service-common`.

## 2. Универсальный `selectDto`

Статический импорт:

```java
import static com.argent.servicecommon.querydsl.ArgentQuerydsl.selectDto;
```

Пример (ниже используется уже существующая сущность `User`):

```java
var qUser = QUser.user;

List<UserRowDto> rows = queryFactory
    .select(selectDto(UserRowDto.class,
        qUser.id,
        qUser.login.as("username"),
        qUser.displayName.as("profile$fullName")))
    .from(qUser)
    .where(qUser.enabled.isTrue())
    .fetch();
```

DTO:

```java
@Getter @Setter @NoArgsConstructor
public class UserRowDto {
    private UUID id;
    private String username;
    private ProfileDto profile;
}

@Getter @Setter @NoArgsConstructor
public class ProfileDto {
    private String fullName;
}
```

Алиас `profile$fullName` задаёт `dto.profile.fullName`. Вложенность произвольная, например
`dto$insideDto$insideDtoField` задаёт `result.dto.insideDto.insideDtoField` (при условии, что поля `dto`, `insideDto` и `insideDtoField` реально существуют в классах DTO).

Поддерживаются:
- Простые пути без `.as(...)`, например `qUser.id` → `dto.id`.
- Простые алиасы (`qUser.login.as("username")`).
- Вложенные алиасы через `$` (`.as("profile$fullName")`).
- Выражения вроде `qUser.login.concat("-suffix").as("username")`; для вычисляемого выражения алиас обязателен.
- `selectDto(Dto.class, List<? extends Expression<?>>)` для динамически собранного списка полей.

**Особенности и ограничения:** DTO и промежуточные вложенные типы должны быть изменяемыми Java-классами с конструктором без аргументов и полями (включая приватные); records и коллекции как промежуточные узлы не поддерживаются. Для значения `null` промежуточные объекты **не создаются** (если все вложенные значения null, вложенный DTO остаётся null). Если поле DTO отсутствует, тип несовместим либо алиас повторён, ошибка возникает сразу при создании проекции. Проекция пишет в поля напрямую (сеттеры не вызываются). Алиас не передаётся в JPQL: в запросе участвует исходное QueryDSL-выражение, а сопоставление происходит при сборке DTO в Java.

## 3. Универсальная пагинация

### 3.1. Автоматический COUNT — обычные запросы

Для простого запроса с `FROM`, `WHERE`, обычными `JOIN` создавать `countQuery` не требуется:

```java
import static com.argent.servicecommon.querydsl.ArgentQuerydsl.paginate;

var qUser = QUser.user;

JPAQuery<UserRowDto> query = queryFactory
    .select(selectDto(UserRowDto.class,
        qUser.id,
        qUser.login.as("username"),
        qUser.displayName.as("profile$fullName")))
    .from(qUser)
    .where(qUser.enabled.isTrue());

Page<UserRowDto> page = paginate(query, PageRequest.of(0, 20));
```

Под капотом Querydsl 7.7 выполняет `fetchCount()` по тому же JPQL-запросу, строя отдельный `COUNT` без `ORDER BY` и `LIMIT`, с сохранением `FROM / JOIN / WHERE` и параметров. `fetchCount` в Querydsl помечен `deprecated`, потому что **в общем случае** подсчёт для сложных JPQL-запросов не гарантирован. ArgentQuerydsl ограничивает автоподсчёт обычными запросами; при неподдерживаемой конструкции выдаёт явную ошибку.

Для сортировки через `Pageable` передайте whitelist полей:

```java
Page<UserRowDto> page = paginate(
    query,
    PageRequest.of(0, 20, Sort.by("username").ascending()),
    Map.of("username", qUser.login, "id", qUser.id)
);
```

Имена сортировки привязаны к Map: неизвестные поля вызывают `IllegalArgumentException`. Направление, ignoreCase для строк, nullsFirst и nullsLast сохраняются.

**Оптимизация:** если на первой странице меньше записей, чем размер страницы, либо получена непустая короткая последняя страница, метод вычислит total по размеру результата и пропустит запрос `COUNT`. Если запрошенная страница пуста при offset > 0 — COUNT всё же выполняется, чтобы верно определить количество записей.

### 3.2. Явный COUNT — сложные запросы

Для `GROUP BY`, `HAVING`, `DISTINCT`, `FETCH JOIN` автоматический режим запрещён; передайте отдельный `countQuery`:

```java
var qUser = QUser.user;

JPAQuery<UserRowDto> query = queryFactory
    .select(selectDto(UserRowDto.class,
        qUser.id, qUser.login.as("username")))
    .from(qUser)
    .where(qUser.enabled.isTrue());

JPAQuery<Long> countQuery = queryFactory
    .select(qUser.id.count())
    .from(qUser)
    .where(qUser.enabled.isTrue());

Page<UserRowDto> page = paginate(
    query,
    countQuery,
    pageable,
    Map.of("username", qUser.login, "id", qUser.id)
);
```

Для `DISTINCT` и соединений, размножающих корневые строки (например, 1:N), вручную считайте **число логических строк** — часто `countDistinct(qUser.id)`. Автоматический COUNT при обычных `JOIN` считает строки результата JPQL; если нужны уникальные корневые сущности, используйте корректный `DISTINCT`-запрос и явный count. Для агрегирующих SELECT (включая `count`, `sum`, `avg` без `groupBy`) также выбирайте ручной count: автоподсчёт считает строки источника, а не количество строк агрегированного результата.

**Ограничения:** пагинация по 1:N `FETCH JOIN` может применяться в памяти Hibernate и приводить к неправильным страницам. Для такого случая рекомендуются две фазы: сначала страница идентификаторов, затем загрузка данных по этим идентификаторам, с count отдельно. Передаваемый `contentQuery` меняется (`orderBy`, `offset`, `limit`), поэтому формируйте новый запрос для каждого вызова. Для стабильной сортировки добавляйте уникальный вторичный ключ (`id`).

`Pageable.unpaged()` поддерживается обоими режимами; выборка выполняется без `COUNT`.

## 4. Публичное API

- `ArgentQuerydsl.selectDto(Dto.class, Expression<?>...)`
- `ArgentQuerydsl.selectDto(Dto.class, List<? extends Expression<?>>)`
- `ArgentQuerydsl.paginate(contentQuery, pageable)` — автоматический COUNT
- `ArgentQuerydsl.paginate(contentQuery, pageable, sortableFields)` — автоматический COUNT + сортировка
- `ArgentQuerydsl.paginate(contentQuery, countQuery, pageable)` — ручной COUNT
- `ArgentQuerydsl.paginate(contentQuery, countQuery, pageable, sortableFields)` — ручной COUNT + сортировка

## 5. Команды

```powershell
cd D:\Projects\AtomSkills\service-common
.\mvnw.cmd clean install

# Только при необходимости выполнить тесты:
.\mvnw.cmd test -DskipTests=false
```

Существующее свойство `<skipTests>true</skipTests>` сохраняется: обычный `install` компилирует тесты, но не запускает их.

Проверьте наличие `target/generated-sources/java/com/argent/servicecommon/auth/entity/QUser.java` после `compile`. `main-backend` после установки новой библиотеки нужно обновить в Maven-проектах IntelliJ IDEA.

## 6. Maven APT для сущностей в `main-backend` / других приложениях

В POM проекта с **собственными** JPA-сущностями в `maven-compiler-plugin` добавьте в `annotationProcessorPaths` (не заменяя Lombok/другие процессоры):

```xml
<path>
    <groupId>io.github.openfeign.querydsl</groupId>
    <artifactId>querydsl-apt</artifactId>
    <version>7.7</version>
    <classifier>jpa</classifier>
</path>
<path>
    <groupId>jakarta.persistence</groupId>
    <artifactId>jakarta.persistence-api</artifactId>
    <version>3.2.0</version>
</path>
```

И укажите `<proc>full</proc>`, `<generatedSourcesDirectory>${project.build.directory}/generated-sources/java</generatedSourcesDirectory>`. Java-класс `@Entity` в приложении будет преобразован в `Q<Entity>` в этом же модуле. Сам `querydsl-jpa` приходит транзитивно из `service-common`.

---

Тесты включены в патч; фактическую Maven-сборку в данной среде провести не удалось из-за отсутствия доступа к Maven Central. Проверьте `mvn clean compile`, затем `mvn test -DskipTests=false` в своём окружении.
