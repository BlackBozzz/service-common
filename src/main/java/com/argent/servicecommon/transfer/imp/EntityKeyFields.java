package com.argent.servicecommon.transfer.imp;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ключевые поля UPSERT по умолчанию, найденные рефлексией по JPA-сущности.
 *
 * <p>Порядок: первое поле с {@code @Column(unique = true)} или {@code @JoinColumn(unique = true)};
 * иначе первое ограничение {@code @Table(uniqueConstraints)}, имена колонок которого переводятся в
 * имена полей (явное {@code @Column(name)}/{@code @JoinColumn(name)}, иначе имя поля в snake_case,
 * для {@code @ManyToOne}/{@code @OneToOne} — с суффиксом {@code _id}). Если хотя бы одна колонка
 * ограничения не найдена среди полей или уникального ключа нет, возвращается пустой список.</p>
 */
public final class EntityKeyFields {

    private EntityKeyFields() {
    }

    /** Ключевые поля сущности или пустой список, если уникальный ключ не объявлен. */
    public static List<String> derive(Class<?> entityClass) {
        List<Field> fields = persistentFields(entityClass);
        for (Field field : fields) {
            if (field.isAnnotationPresent(Id.class)) continue;
            Column column = field.getAnnotation(Column.class);
            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            if (column != null && column.unique() || joinColumn != null && joinColumn.unique()) {
                return List.of(field.getName());
            }
        }
        Table table = entityClass.getAnnotation(Table.class);
        if (table == null || table.uniqueConstraints().length == 0) {
            return List.of();
        }
        UniqueConstraint constraint = table.uniqueConstraints()[0];
        List<String> result = new ArrayList<>();
        for (String columnName : constraint.columnNames()) {
            String fieldName = fields.stream()
                    .filter(field -> columnNames(field).contains(normalize(columnName)))
                    .map(Field::getName).findFirst().orElse(null);
            if (fieldName == null) {
                return List.of();
            }
            result.add(fieldName);
        }
        return List.copyOf(result);
    }

    private static List<Field> persistentFields(Class<?> entityClass) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = entityClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            hierarchy.addFirst(current);
        }
        List<Field> result = new ArrayList<>();
        for (Class<?> type : hierarchy) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())
                        || Modifier.isTransient(field.getModifiers())) continue;
                result.add(field);
            }
        }
        return result;
    }

    private static List<String> columnNames(Field field) {
        List<String> names = new ArrayList<>();
        Column column = field.getAnnotation(Column.class);
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (column != null && !column.name().isBlank()) names.add(normalize(column.name()));
        if (joinColumn != null && !joinColumn.name().isBlank()) names.add(normalize(joinColumn.name()));
        if (names.isEmpty()) {
            boolean association = field.isAnnotationPresent(ManyToOne.class)
                    || field.isAnnotationPresent(OneToOne.class);
            String snake = snakeCase(field.getName());
            names.add(association ? snake + "_id" : snake);
            names.add(normalize(association ? field.getName() + "_id" : field.getName()));
        }
        return names;
    }

    private static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String normalize(String name) {
        return name.replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
    }
}
