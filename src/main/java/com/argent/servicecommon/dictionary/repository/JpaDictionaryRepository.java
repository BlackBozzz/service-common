
package com.argent.servicecommon.dictionary.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Универсальная реализация репозитория справочников на основе JPA.
 *
 * <p>Позволяет выполнять CRUD-операции над различными
 * JPA-сущностями без создания отдельного репозитория
 * для каждого справочника.</p>
 *
 * <p>Проверка регистрации справочника, прав доступа,
 * преобразование DTO и управление транзакциями
 * выполняются на уровне сервисного слоя.</p>
 *
 * <p>Реализация не регистрируется автоматически
 * как Spring-компонент. Создание бина выполняется
 * через автоконфигурацию библиотеки.</p>
 */
@RequiredArgsConstructor
public class JpaDictionaryRepository implements DictionaryRepository {

    private final EntityManager entityManager;

    /**
     * Находит запись справочника по идентификатору.
     *
     * @param entityClass класс JPA-сущности
     * @param id идентификатор записи
     * @param <T> тип сущности
     * @return найденная запись или пустой результат
     * @throws NullPointerException если аргумент равен null
     */
    @Override
    public <T> Optional<T> findById(Class<T> entityClass, Object id) {

        Objects.requireNonNull(
                entityClass,
                "Класс сущности не может быть null."
        );

        Objects.requireNonNull(
                id,
                "Идентификатор записи не может быть null."
        );

        // EntityManager самостоятельно определяет таблицу
        // и поле первичного ключа по метаданным JPA.
        T entity = entityManager.find(entityClass, id);

        // Если запись отсутствует, возвращаем Optional.empty().
        return Optional.ofNullable(entity);
    }

    /**
     * Возвращает все записи указанного справочника.
     *
     * <p>Запрос строится через Criteria API, поэтому
     * не зависит от конкретного класса сущности
     * и физического имени таблицы.</p>
     *
     * <p>Метод не использует пагинацию. Для больших
     * справочников необходимо предусмотреть отдельный
     * метод постраничного получения данных.</p>
     *
     * @param entityClass класс JPA-сущности
     * @param <T> тип сущности
     * @return список записей; пустой список, если записей нет
     * @throws NullPointerException если класс сущности равен null
     */
    @Override
    public <T> List<T> findAll(Class<T> entityClass) {

        Objects.requireNonNull(
                entityClass,
                "Класс сущности не может быть null."
        );

        // Получаем фабрику типизированных запросов JPA.
        var criteriaBuilder = entityManager.getCriteriaBuilder();

        // Создаём запрос, возвращающий объекты нужного типа.
        var query = criteriaBuilder.createQuery(entityClass);

        // Указываем сущность, которая будет источником данных.
        var root = query.from(entityClass);

        // Выбираем все записи указанной сущности.
        query.select(root);

        // Выполняем запрос.
        // Порядок записей не гарантирован без ORDER BY.
        return entityManager
                .createQuery(query)
                .getResultList();
    }

    /**
     * Создаёт новую запись справочника.
     *
     * <p>Переданная сущность должна быть новой.
     * После вызова persist она становится управляемой
     * текущим контекстом JPA.</p>
     *
     * <p>Фактическая вставка записи может произойти
     * при flush или завершении транзакции.
     * Для стратегии IDENTITY вставка может
     * потребоваться раньше.</p>
     *
     * @param entity новая JPA-сущность
     * @param <T> тип сущности
     * @return управляемая сущность
     * @throws NullPointerException если сущность равна null
     */
    @Override
    public <T> T create(T entity) {

        Objects.requireNonNull(
                entity,
                "Создаваемая сущность не может быть null."
        );

        // Регистрируем новую сущность в текущем
        // контексте JPA для последующей вставки в БД.
        entityManager.persist(entity);

        // Возвращаем тот же экземпляр:
        // persist не создаёт его копию.
        return entity;
    }

    /**
     * Обновляет состояние существующей записи справочника.
     *
     * <p>Метод поддерживает как управляемые, так и
     * отсоединённые (detached) JPA-сущности.</p>
     *
     * <p>Важно: merge также может сохранять новые сущности.
     * Поэтому проверка существования записи перед обновлением
     * должна выполняться в сервисном слое.</p>
     *
     * @param entity сущность с изменёнными данными
     * @param <T> тип сущности
     * @return управляемый экземпляр обновлённой сущности
     * @throws NullPointerException если сущность равна null
     */
    @Override
    public <T> T update(T entity) {

        Objects.requireNonNull(
                entity,
                "Обновляемая сущность не может быть null."
        );

        return entityManager.merge(entity);
    }

    /**
     * Удаляет запись справочника.
     *
     * <p>Метод поддерживает управляемые и отсоединённые
     * JPA-сущности. Для отсоединённой сущности сначала
     * загружается управляемый экземпляр по первичному ключу.</p>
     *
     * <p>Если соответствующая запись уже отсутствует в БД,
     * метод завершается без выполнения удаления.</p>
     *
     * @param entity удаляемая JPA-сущность
     * @param <T> тип сущности
     * @throws NullPointerException если сущность равна null
     */
    @Override
    public <T> void delete(T entity) {

        Objects.requireNonNull(
                entity,
                "Удаляемая сущность не может быть null."
        );

        // Если сущность уже управляется текущим
        // контекстом JPA, удаляем её непосредственно.
        if (entityManager.contains(entity)) {
            entityManager.remove(entity);
            return;
        }

        // Для detached-сущности определяем тип
        // и значение первичного ключа через JPA.
        var persistenceUnitUtil =
                entityManager.getEntityManagerFactory()
                        .getPersistenceUnitUtil();

        Object id = persistenceUnitUtil.getIdentifier(entity);

        // Для новой сущности без идентификатора
        // в базе данных нечего удалять.
        if (id == null) {
            return;
        }

        // Получаем класс JPA-сущности через метамодель.
        // Это учитывает наследование и прокси Hibernate.
        var entityType = entityManager.getMetamodel()
                .entity(entity.getClass());

        // Загружаем актуальный управляемый экземпляр.
        Object managedEntity = entityManager.find(
                entityType.getJavaType(),
                id
        );

        // Если запись существует, помечаем её на удаление.
        if (managedEntity != null) {
            entityManager.remove(managedEntity);
        }
    }
}
