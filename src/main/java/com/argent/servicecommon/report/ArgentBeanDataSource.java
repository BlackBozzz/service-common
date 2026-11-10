package com.argent.servicecommon.report;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;
import net.sf.jasperreports.engine.JRRewindableDataSource;

/**
 * Источник данных отчёта по коллекции строк: record, Map, бины с геттерами и публичными полями.
 * Поддерживает вложенные пути через точку и приведение значения к классу поля шаблона.
 */
class ArgentBeanDataSource implements JRRewindableDataSource {

    @FunctionalInterface
    private interface Accessor {
        Object read(Object bean) throws ReflectiveOperationException;
    }

    private static final Map<Class<?>, Map<String, Accessor>> ACCESSORS = new ConcurrentHashMap<>();

    private final Collection<?> rows;
    private Iterator<?> iterator;
    private Object current;

    ArgentBeanDataSource(Collection<?> rows) {
        this.rows = rows;
        this.iterator = rows.iterator();
    }

    @Override
    public boolean next() {
        if (!iterator.hasNext()) {
            return false;
        }
        current = iterator.next();
        return true;
    }

    @Override
    public void moveFirst() {
        iterator = rows.iterator();
        current = null;
    }

    @Override
    public Object getFieldValue(JRField field) throws JRException {
        Object value = readProperty(current, field.getName());
        try {
            return ReportValues.convert(value, field.getValueClass());
        } catch (IllegalArgumentException e) {
            throw new ReportRenderException("Поле \"" + field.getName() + "\": " + e.getMessage(), e);
        }
    }

    static Object readProperty(Object bean, String path) {
        Object value = bean;
        for (String key : path.split("[.]")) {
            if (value == null) {
                return null;
            }
            value = readStep(value, key, path);
        }
        return value;
    }

    private static Object readStep(Object bean, String key, String path) {
        if (bean instanceof Map<?, ?> map) {
            return map.get(key);
        }
        Accessor accessor = ACCESSORS
                .computeIfAbsent(bean.getClass(), c -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> findAccessor(bean.getClass(), k));
        if (accessor == null) {
            throw new ReportRenderException("В строках отчёта нет свойства \"" + path
                    + "\" (класс " + bean.getClass().getSimpleName() + ")");
        }
        try {
            return accessor.read(bean);
        } catch (ReflectiveOperationException e) {
            throw new ReportRenderException("Не удалось прочитать свойство \"" + key + "\" (класс "
                    + bean.getClass().getSimpleName() + ")", e);
        }
    }

    /** Возвращает {@code null}, если свойство не найдено (ConcurrentHashMap не хранит null, поэтому не кэшируется). */
    private static Accessor findAccessor(Class<?> type, String name) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(name)) {
                    return methodAccessor(component.getAccessor());
                }
            }
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : new String[] {"get", "is"}) {
            try {
                Method method = type.getMethod(prefix + suffix);
                if (method.getReturnType() != void.class && !Modifier.isStatic(method.getModifiers())) {
                    return methodAccessor(method);
                }
            } catch (NoSuchMethodException ignored) {
                // пробуем следующий вариант
            }
        }
        try {
            Field field = type.getField(name);
            field.trySetAccessible();
            return field::get;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Accessor methodAccessor(Method method) {
        method.trySetAccessible();
        return method::invoke;
    }
}
