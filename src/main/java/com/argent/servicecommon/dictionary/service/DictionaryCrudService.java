
package com.argent.servicecommon.dictionary.service;

import com.argent.servicecommon.dictionary.repository.DictionaryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Универсальный сервис для выполнения CRUD-операций
 * над зарегистрированными справочниками.
 *
 * <p>Код справочника используется для поиска сущности
 * в реестре common_dictionaries. Запрошенный класс
 * дополнительно проверяется по JPA-метамодели.</p>
 *
 * <p>Методы чтения возвращают типизированные сущности.
 * Методы создания и обновления сохраняют тип переданного объекта.</p>
 *
 * <p>Сервис работает с JPA-сущностями. Преобразование DTO,
 * авторизация и проверка разрешённых для редактирования полей
 * выполняются на вышестоящем уровне.</p>
 *
 * <p>Для вызовов из REST API необходимо дополнительно
 * предусмотреть обязательную пагинацию и обработку ошибок.</p>
 */
@RequiredArgsConstructor
public class DictionaryCrudService {

    private final DictionaryRepository dictionaryRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Возвращает запись справочника по идентификатору.
     *
     * @param code код справочника
     * @param id строковое представление идентификатора
     * @param entityClass ожидаемый класс сущности
     * @param <T> тип сущности
     * @return найденная сущность
     */
    @Transactional(readOnly = true)
    public <T> T findById(
            String code,
            String id,
            Class<T> entityClass
    ) {
        Class<T> verifiedClass = resolveEntityClass(code, entityClass);
        Object convertedId = convertId(verifiedClass, id);

        // Поиск выполняется с типом, проверенным по реестру.
        return dictionaryRepository
                .findById(verifiedClass, convertedId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Запись справочника '%s' с идентификатором '%s' не найдена."
                                .formatted(code, id)
                ));
    }

    /**
     * Возвращает все записи зарегистрированного справочника.
     *
     * <p>Порядок записей не гарантирован.
     * Метод не ограничивает размер результата.</p>
     *
     * @param code код справочника
     * @param entityClass ожидаемый класс сущности
     * @param <T> тип сущности
     * @return список типизированных сущностей
     */
    @Transactional(readOnly = true)
    public <T> List<T> findAll(
            String code,
            Class<T> entityClass
    ) {
        Class<T> verifiedClass = resolveEntityClass(code, entityClass);

        // Criteria API в репозитории сформирует запрос
        // для указанного класса JPA-сущности.
        return dictionaryRepository.findAll(verifiedClass);
    }

    /**
     * Создаёт новую запись зарегистрированного справочника.
     *
     * @param code код справочника
     * @param entity новая JPA-сущность
     * @param <T> тип сущности
     * @return созданная сущность
     */
    @Transactional
    public <T> T create(String code, T entity) {
        Objects.requireNonNull(
                entity,
                "Создаваемая сущность не может быть null."
        );

        Class<?> registeredClass = resolveEntityClass(code);

        // Запрещаем использовать код одного справочника
        // для сохранения сущности другого справочника.
        validateEntityClass(registeredClass, entity);

        // Проверка, что сущность действительно новая,
        // зависит от стратегии генерации идентификатора.
        // Эту семантику дополнительно защищает persist().
        return dictionaryRepository.create(entity);
    }

    /**
     * Обновляет существующую запись справочника.
     *
     * <p>Проверяет существование записи и соответствие
     * идентификатора сущности идентификатору запроса.</p>
     *
     * <p>Использует полное состояние переданной сущности.
     * Для частичного обновления полей необходим отдельный метод.</p>
     *
     * @param code код справочника
     * @param id строковое представление идентификатора
     * @param entity сущность с изменёнными данными
     * @param <T> тип сущности
     * @return управляемая обновлённая сущность
     */
    @Transactional
    public <T> T update(
            String code,
            String id,
            T entity
    ) {
        Objects.requireNonNull(
                entity,
                "Обновляемая сущность не может быть null."
        );

        Class<?> registeredClass = resolveEntityClass(code);
        validateEntityClass(registeredClass, entity);

        Object convertedId = convertId(registeredClass, id);

        // Извлекаем ID без предположений о названии поля:
        // он может называться id, code, uuid и так далее.
        Object actualId = entityManager
                .getEntityManagerFactory()
                .getPersistenceUnitUtil()
                .getIdentifier(entity);

        if (!Objects.equals(convertedId, actualId)) {
            throw new IllegalArgumentException(
                    "Идентификатор обновляемой сущности "
                            + "не совпадает с идентификатором запрошенной записи."
            );
        }

        // Не допускаем обновления отсутствующей записи:
        // merge() не предназначен для обеспечения
        // семантики «обновить только существующую запись».
        if (dictionaryRepository
                .findById(registeredClass, convertedId)
                .isEmpty()) {
            throw new IllegalArgumentException(
                    "Невозможно обновить запись справочника '%s' "
                            .formatted(code)
                            + "с идентификатором '%s': запись не найдена."
                            .formatted(id)
            );
        }

        // Репозиторий возвращает управляемый результат merge().
        return dictionaryRepository.update(entity);
    }

    /**
     * Удаляет существующую запись справочника по идентификатору.
     *
     * @param code код справочника
     * @param id строковое представление идентификатора
     */
    @Transactional
    public void delete(String code, String id) {
        Class<?> registeredClass = resolveEntityClass(code);
        Object convertedId = convertId(registeredClass, id);

        // Передаём в репозиторий управляемую сущность,
        // загруженную внутри текущей транзакции.
        Object entity = dictionaryRepository
                .findById(registeredClass, convertedId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Невозможно удалить запись справочника '%s' "
                                .formatted(code)
                                + "с идентификатором '%s': запись не найдена."
                                .formatted(id)
                ));

        dictionaryRepository.delete(entity);
    }

    /**
     * Находит зарегистрированный класс JPA-сущности
     * и проверяет его соответствие ожидаемому классу.
     *
     * @param code код справочника
     * @param expectedClass ожидаемый класс
     * @param <T> тип сущности
     * @return проверенный класс сущности
     */
    private <T> Class<T> resolveEntityClass(
            String code,
            Class<T> expectedClass
    ) {
        Objects.requireNonNull(
                expectedClass,
                "Ожидаемый класс сущности не может быть null."
        );

        Class<?> registeredClass = resolveEntityClass(code);

        // Проверяем точное совпадение классов.
        // Это исключает ошибочную типизацию результата.
        if (!registeredClass.equals(expectedClass)) {
            throw new IllegalArgumentException(
                    "Для справочника '%s' зарегистрирована сущность '%s', "
                            .formatted(code, registeredClass.getName())
                            + "но запрошена сущность '%s'."
                            .formatted(expectedClass.getName())
            );
        }

        return expectedClass;
    }

    /**
     * Определяет JPA-сущность по реестру common_dictionaries.
     *
     * <p>В текущей модели колонка entity_class содержит
     * простое имя Java-класса, а code — имя таблицы.</p>
     *
     * @param code код справочника
     * @return зарегистрированный класс сущности
     */
    private Class<?> resolveEntityClass(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                    "Код справочника не может быть пустым."
            );
        }

        // Читаем имя сущности из реестра.
        // Значение кода передаётся параметром, а не
        // объединяется с SQL-строкой.
        List<String> entityNames = jdbcTemplate.queryForList(
                """
                select entity_class
                from common_dictionaries
                where code = ?
                """,
                String.class,
                code
        );

        if (entityNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "Справочник с кодом '%s' не зарегистрирован."
                            .formatted(code)
            );
        }

        if (entityNames.size() != 1) {
            throw new IllegalStateException(
                    "В реестре обнаружено несколько записей "
                            + "для справочника '%s'."
                            .formatted(code)
            );
        }

        String registeredEntityName = entityNames.getFirst();

        // Ищем только среди сущностей текущего persistence unit.
        // Не загружаем произвольные классы по имени из БД.
        for (EntityType<?> entityType :
                entityManager.getMetamodel().getEntities()) {

            Class<?> candidateClass = entityType.getJavaType();

            if (!candidateClass.getSimpleName()
                    .equals(registeredEntityName)) {
                continue;
            }

            Table table = candidateClass.getAnnotation(Table.class);

            // Дополнительно сверяем имя физической таблицы.
            if (table != null && code.equals(table.name())) {
                return candidateClass;
            }
        }

        throw new IllegalStateException(
                "JPA-сущность для справочника '%s' не найдена. "
                        .formatted(code)
                        + "Проверьте реестр common_dictionaries "
                        + "и метаданные Hibernate."
        );
    }

    /**
     * Преобразует идентификатор из строки
     * в Java-тип первичного ключа JPA-сущности.
     *
     * <p>Составные идентификаторы не поддерживаются.</p>
     *
     * @param entityClass класс сущности
     * @param id строковое представление идентификатора
     * @return типизированный идентификатор
     */
    private Object convertId(
            Class<?> entityClass,
            String id
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Идентификатор записи не может быть пустым."
            );
        }

        EntityType<?> entityType = entityManager
                .getMetamodel()
                .entity(entityClass);

        if (!entityType.hasSingleIdAttribute()) {
            throw new IllegalArgumentException(
                    "Составные идентификаторы пока не поддерживаются "
                            + "универсальным сервисом справочников."
            );
        }

        Class<?> idType = entityType
                .getIdType()
                .getJavaType();

        try {
            if (idType == String.class) {
                return id;
            }

            if (idType == Long.class || idType == long.class) {
                return Long.valueOf(id);
            }

            if (idType == Integer.class || idType == int.class) {
                return Integer.valueOf(id);
            }

            if (idType == Short.class || idType == short.class) {
                return Short.valueOf(id);
            }

            if (idType == Byte.class || idType == byte.class) {
                return Byte.valueOf(id);
            }

            if (idType == UUID.class) {
                return UUID.fromString(id);
            }

            if (idType == BigInteger.class) {
                return new BigInteger(id);
            }

            if (idType == BigDecimal.class) {
                return new BigDecimal(id);
            }

        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Некорректный идентификатор '%s'. Ожидаемый тип: %s."
                            .formatted(id, idType.getSimpleName()),
                    exception
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Некорректный идентификатор '%s'. Ожидаемый тип: %s."
                            .formatted(id, idType.getSimpleName()),
                    exception
            );
        }

        throw new IllegalArgumentException(
                "Неподдерживаемый тип идентификатора: "
                        + idType.getName()
        );
    }

    /**
     * Проверяет, что переданный объект принадлежит
     * зарегистрированному классу JPA-сущности.
     *
     * @param expectedClass класс из реестра
     * @param entity проверяемая сущность
     */
    private void validateEntityClass(
            Class<?> expectedClass,
            Object entity
    ) {
        // isInstance допускает экземпляры подклассов,
        // в том числе Hibernate-прокси.
        if (!expectedClass.isInstance(entity)) {
            throw new IllegalArgumentException(
                    "Переданная сущность имеет тип '%s', "
                            .formatted(entity.getClass().getName())
                            + "но ожидается тип '%s'."
                            .formatted(expectedClass.getName())
            );
        }
    }
}
