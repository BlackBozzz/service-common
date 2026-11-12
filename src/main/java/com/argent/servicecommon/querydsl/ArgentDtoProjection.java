package com.argent.servicecommon.querydsl;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.FactoryExpressionBase;
import com.querydsl.core.types.Operation;
import com.querydsl.core.types.Ops;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.Visitor;

import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Querydsl-проекция в изменяемый DTO с поддержкой вложенных полей через знак '$'.
 *
 * <p>Алиас {@code address$city$name} соответствует {@code dto.address.city.name}.
 * DTO и вложенные классы должны иметь конструктор без аргументов и изменяемые поля.
 * Если значение равно null, вложенные объекты для него не создаются.</p>
 *
 * <p>Алиасы используются только на Java-стороне: в JPQL передаются исходные выражения.
 * Это устраняет проблемы с символом '$' в JPQL-алиасах.</p>
 *
 * @param <D> тип DTO
 */
public final class ArgentDtoProjection<D> extends FactoryExpressionBase<D> {

    @Serial
    private static final long serialVersionUID = 1L;

    private final List<Expression<?>> args;
    private final List<List<Field>> paths;
    private final Constructor<D> constructor;

    public ArgentDtoProjection(Class<D> dtoType, List<? extends Expression<?>> selections) {
        super(Objects.requireNonNull(dtoType, "DTO class must not be null"));
        Objects.requireNonNull(selections, "Selections must not be null");
        if (selections.isEmpty()) {
            throw new IllegalArgumentException("At least one selection is required for " + dtoType.getName());
        }

        this.constructor = constructorFor(dtoType);
        List<Expression<?>> sqlArgs = new ArrayList<>(selections.size());
        List<List<Field>> fieldPaths = new ArrayList<>(selections.size());
        Set<String> usedAliases = new HashSet<>();

        for (Expression<?> selection : selections) {
            Objects.requireNonNull(selection, "Projection selection must not be null");
            Expression<?> extracted = ExpressionUtils.extract(selection);
            final String alias;
            final Expression<?> source;

            if (extracted instanceof Operation<?> operation && operation.getOperator() == Ops.ALIAS) {
                if (!(operation.getArg(1) instanceof Path<?> aliasPath)) {
                    throw new IllegalArgumentException("Projection alias must be a Querydsl path: " + selection);
                }
                alias = aliasPath.getMetadata().getName();
                source = operation.getArg(0);
            } else if (extracted instanceof Path<?> path) {
                // Without .as(...), use the terminal property name of the selected path.
                alias = path.getMetadata().getName();
                source = selection;
            } else {
                throw new IllegalArgumentException(
                        "Calculated expressions require .as(\"dtoField\"): " + selection);
            }

            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("Projection alias must not be empty");
            }
            if (!usedAliases.add(alias)) {
                throw new IllegalArgumentException("Duplicate DTO projection alias: " + alias);
            }

            String[] segments = alias.split("\\$", -1);
            for (String segment : segments) {
                if (segment.isEmpty()) {
                    throw new IllegalArgumentException("Invalid nested DTO alias: " + alias);
                }
            }
            List<Field> resolved = resolveFields(dtoType, segments);
            Class<?> fieldType = box(resolved.get(resolved.size() - 1).getType());
            Class<?> sourceType = box(source.getType());
            if (!fieldType.isAssignableFrom(sourceType)) {
                throw new IllegalArgumentException("Alias '" + alias + "' requires " + fieldType.getName()
                        + ", but expression returns " + sourceType.getName());
            }

            sqlArgs.add(source);
            fieldPaths.add(resolved);
        }

        for (String alias : usedAliases) {
            // A leaf cannot simultaneously contain a whole object and one of its fields.
            String prefix = alias + "$";
            if (usedAliases.stream().anyMatch(other -> other.startsWith(prefix))) {
                throw new IllegalArgumentException("Conflicting DTO projection aliases: " + alias
                        + " and a nested path starting with " + prefix);
            }
        }

        this.args = List.copyOf(sqlArgs);
        this.paths = List.copyOf(fieldPaths);
    }

    @Override
    public List<Expression<?>> getArgs() {
        return args;
    }

    @Override
    public D newInstance(Object... values) {
        if (values == null || values.length != paths.size()) {
            throw new IllegalArgumentException("Expected " + paths.size() + " projection values, got "
                    + (values == null ? "null" : values.length));
        }
        D result = instantiate(constructor);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value == null) {
                continue; // Keep the nested DTO null when all its selected fields are null.
            }
            List<Field> path = paths.get(i);
            Object target = result;
            for (int p = 0; p < path.size() - 1; p++) {
                Field field = path.get(p);
                Object nested = get(field, target);
                if (nested == null) {
                    nested = instantiate(constructorFor(field.getType()));
                    set(field, target, nested);
                }
                target = nested;
            }
            set(path.get(path.size() - 1), target, value);
        }
        return result;
    }

    @Override
    public <R, C> R accept(Visitor<R, C> visitor, C context) {
        return visitor.visit(this, context);
    }

    private static List<Field> resolveFields(Class<?> dtoClass, String[] segments) {
        List<Field> fields = new ArrayList<>(segments.length);
        Class<?> type = dtoClass;
        for (int i = 0; i < segments.length; i++) {
            Field field = findField(type, segments[i]);
            if (field == null) {
                throw new IllegalArgumentException("DTO field '" + segments[i] + "' not found on "
                        + type.getName() + " for alias " + String.join("$", segments));
            }
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                throw new IllegalArgumentException("DTO field must be mutable: " + field);
            }
            if (!field.trySetAccessible()) {
                throw new IllegalArgumentException("DTO field is inaccessible: " + field);
            }
            fields.add(field);
            type = field.getType();
            if (i < segments.length - 1) {
                constructorFor(type); // Fail fast for an unsupported intermediate type.
            }
        }
        return List.copyOf(fields);
    }

    private static Field findField(Class<?> dtoType, String name) {
        for (Class<?> current = dtoType; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Inherited DTO fields are also supported.
            }
        }
        return null;
    }

    private static <T> Constructor<T> constructorFor(Class<T> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type.isRecord()) {
            throw new IllegalArgumentException("DTO must be a mutable class with a no-args constructor: "
                    + type.getName());
        }
        try {
            Constructor<T> ctor = type.getDeclaredConstructor();
            if (!ctor.trySetAccessible()) {
                throw new IllegalArgumentException("DTO constructor is inaccessible: " + type.getName());
            }
            return ctor;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("DTO requires a no-args constructor: " + type.getName(), e);
        }
    }

    private static <T> T instantiate(Constructor<T> constructor) {
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate DTO "
                    + constructor.getDeclaringClass().getName(), e);
        }
    }

    private static Object get(Field field, Object object) {
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to read DTO field " + field, e);
        }
    }

    private static void set(Field field, Object object, Object value) {
        try {
            field.set(object, value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to write DTO field " + field
                    + " with value " + value, e);
        }
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
