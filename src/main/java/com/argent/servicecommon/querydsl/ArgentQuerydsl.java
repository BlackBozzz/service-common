package com.argent.servicecommon.querydsl;

import com.querydsl.core.QueryMetadata;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.FactoryExpression;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPAQueryMixin;
import com.querydsl.jpa.impl.JPAQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Универсальные Querydsl-проекции и пагинация для backend-проектов AtomSkills.
 */
public final class ArgentQuerydsl {

    private ArgentQuerydsl() {
    }

    /** Создаёт DTO-проекцию. Для вложенного поля используйте {@code .as("profile$address$city")}. */
    public static <D> FactoryExpression<D> selectDto(
            Class<D> dtoClass,
            Expression<?>... selections
    ) {
        Objects.requireNonNull(selections, "Selections must not be null");
        return new ArgentDtoProjection<>(dtoClass, Arrays.asList(selections));
    }

    /** Создаёт DTO-проекцию из динамического списка Querydsl-выражений. */
    public static <D> FactoryExpression<D> selectDto(
            Class<D> dtoClass,
            List<? extends Expression<?>> selections
    ) {
        return new ArgentDtoProjection<>(dtoClass, selections);
    }

    /**
     * Автоматическая пагинация для простых JPQL-запросов без groupBy/having/distinct/fetchJoin.
     * Querydsl самостоятельно формирует COUNT с сохранением FROM, JOIN и WHERE.
     * Для сложных запросов используйте перегрузку с явно заданным countQuery.
     */
    public static <T> Page<T> paginate(JPAQuery<T> contentQuery, Pageable pageable) {
        return paginate(contentQuery, pageable, Map.of());
    }

    /**
     * Автоматическая пагинация с whitelist сортируемых полей.
     * Ключ map соответствует имени поля в {@link Pageable#getSort()}.
     */
    public static <T> Page<T> paginate(
            JPAQuery<T> contentQuery,
            Pageable pageable,
            Map<String, ? extends Expression<? extends Comparable<?>>> sortableFields
    ) {
        Objects.requireNonNull(contentQuery, "Content query must not be null");
        Objects.requireNonNull(pageable, "Pageable must not be null");
        if (pageable.isPaged()) {
            validateAutomaticCount(contentQuery);
        }
        return paginateInternal(contentQuery, pageable, sortableFields,
                () -> autoCount(contentQuery), true);
    }

    /**
     * Пагинация сложного запроса с явно заданным COUNT.
     * {@code countQuery} должен возвращать одно число — количество логических строк страницы.
     */
    public static <T> Page<T> paginate(
            JPAQuery<T> contentQuery,
            JPAQuery<Long> countQuery,
            Pageable pageable
    ) {
        return paginate(contentQuery, countQuery, pageable, Map.of());
    }

    /** Пагинация сложного запроса с явным COUNT и безопасной сортировкой. */
    public static <T> Page<T> paginate(
            JPAQuery<T> contentQuery,
            JPAQuery<Long> countQuery,
            Pageable pageable,
            Map<String, ? extends Expression<? extends Comparable<?>>> sortableFields
    ) {
        Objects.requireNonNull(countQuery, "Count query must not be null");
        return paginateInternal(contentQuery, pageable, sortableFields,
                () -> {
                    Long count = countQuery.fetchOne();
                    return count == null ? 0L : count;
                }, false);
    }

    /**
     * Для автоматического режима запрещаем конструкции, где Querydsl не может
     * гарантировать корректный или недорогой COUNT через JPQL.
     */
    private static void validateAutomaticCount(JPAQuery<?> contentQuery) {
        QueryMetadata metadata = Objects.requireNonNull(contentQuery.getMetadata(),
                "Content query metadata must not be null");
        if (metadata.isDistinct() || !metadata.getGroupBy().isEmpty()
                || metadata.getHaving() != null
                || metadata.getJoins().stream()
                        .anyMatch(join -> join.getFlags().contains(JPAQueryMixin.FETCH))) {
            throw new IllegalArgumentException(
                    "Automatic COUNT supports only queries without DISTINCT, GROUP BY, HAVING "
                            + "and FETCH JOIN. Pass an explicit countQuery to ArgentQuerydsl.paginate().");
        }
    }

    /**
     * Querydsl 7.7 поддерживает fetchCount для обычных JPQL-запросов; метод помечен
     * deprecated в Querydsl из-за сложных группировок, которые мы запретили выше.
     * В отличие от запроса на отдельный EntityManager, этот способ сохраняет
     * параметры и JOIN/WHERE оригинального запроса и не применяет LIMIT к COUNT.
     */
    @SuppressWarnings("deprecation")
    private static long autoCount(JPAQuery<?> contentQuery) {
        return contentQuery.fetchCount();
    }

    private static <T> Page<T> paginateInternal(
            JPAQuery<T> contentQuery,
            Pageable pageable,
            Map<String, ? extends Expression<? extends Comparable<?>>> sortableFields,
            LongSupplier countSupplier,
            boolean inferTotalOnShortPage
    ) {
        Objects.requireNonNull(contentQuery, "Content query must not be null");
        Objects.requireNonNull(pageable, "Pageable must not be null");
        Objects.requireNonNull(sortableFields, "Sortable fields must not be null");
        Objects.requireNonNull(countSupplier, "Count supplier must not be null");

        for (Sort.Order order : pageable.getSort()) {
            Expression<? extends Comparable<?>> expression = sortableFields.get(order.getProperty());
            if (expression == null) {
                throw new IllegalArgumentException("Unsupported Querydsl sort property: "
                        + order.getProperty() + ". Allowed: " + sortableFields.keySet());
            }
            contentQuery.orderBy(toOrderSpecifier(order, expression));
        }

        if (pageable.isUnpaged()) {
            return new PageImpl<>(contentQuery.fetch());
        }

        long offset = pageable.getOffset();
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Page offset is too large for JPA: " + offset);
        }

        List<T> content = contentQuery
                .offset(offset)
                .limit(pageable.getPageSize())
                .fetch();

        // На последней непустой странице (или на первой пустой) COUNT не нужен.
        // Если страница за пределами данных и пустая, COUNT обязателен: он позволяет
        // отличить пустую таблицу от запрошенной страницы с большим offset.
        if (inferTotalOnShortPage && content.size() < pageable.getPageSize()
                && (offset == 0L || !content.isEmpty())) {
            return new PageImpl<>(content, pageable, offset + content.size());
        }
        return new PageImpl<>(content, pageable, countSupplier.getAsLong());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OrderSpecifier<?> toOrderSpecifier(
            Sort.Order order,
            Expression<? extends Comparable<?>> expression
    ) {
        Expression selected = expression;
        if (order.isIgnoreCase()) {
            if (!(expression instanceof StringExpression text)) {
                throw new IllegalArgumentException("ignoreCase requires a string sort field: "
                        + order.getProperty());
            }
            selected = text.lower();
        }
        OrderSpecifier<?> specifier = new OrderSpecifier(
                order.isAscending() ? Order.ASC : Order.DESC,
                selected
        );
        return switch (order.getNullHandling()) {
            case NULLS_FIRST -> specifier.nullsFirst();
            case NULLS_LAST -> specifier.nullsLast();
            case NATIVE -> specifier;
        };
    }
}
