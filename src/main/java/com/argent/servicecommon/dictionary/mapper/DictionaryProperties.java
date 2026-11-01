package com.argent.servicecommon.dictionary.mapper;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.exception.DictionaryMappingException;

import java.beans.Introspector;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Внутренние операции чтения структуры DTO и доступа к свойствам Java. */
public final class DictionaryProperties {
    private DictionaryProperties() { }

    /** Описание ровно одного поля, явно объявленного в DTO. */
    public record Property(String name, Class<?> type, Type genericType,
                           DictionaryField options) {
        public boolean readOnly() { return options != null && options.readOnly(); }
        public String label() { return options == null ? "" : options.label(); }
        public String description() { return options == null ? "" : options.description(); }
        public int order() { return options == null ? 0 : options.order(); }
        public boolean visible() { return options == null || options.visible(); }
    }

    /** Возвращает только реально объявленные поля DTO, включая record-компоненты. */
    public static List<Property> dtoProperties(Class<?> type) {
        List<Property> result = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                DictionaryField annotation = component.getAnnotation(DictionaryField.class);
                if (annotation == null) {
                    Field field = fieldOrNull(type, component.getName());
                    if (field != null) annotation = field.getAnnotation(DictionaryField.class);
                }
                result.add(new Property(component.getName(), component.getType(),
                        component.getGenericType(), annotation));
            }
        } else {
            // Поля, не объявленные в DTO, не участвуют в преобразовании.
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                    result.add(new Property(field.getName(), field.getType(),
                            field.getGenericType(), field.getAnnotation(DictionaryField.class)));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Находит тип элементов коллекции DTO; сырые коллекции запрещены. */
    public static Class<?> elementClass(Property property) {
        Type generic = property.genericType();
        if (!(generic instanceof ParameterizedType parameterized)
                || parameterized.getActualTypeArguments().length != 1
                || !(parameterized.getActualTypeArguments()[0] instanceof Class<?> element)) {
            throw new DictionaryMappingException(
                    "Поле DTO '%s' должно иметь конкретный тип элемента коллекции."
                            .formatted(property.name()));
        }
        return element;
    }

    /** Получает свойство по геттеру, record-аксессору либо непосредственно из поля. */
    public static Object read(Object bean, String name) {
        try {
            for (var descriptor : Introspector.getBeanInfo(bean.getClass()).getPropertyDescriptors()) {
                if (descriptor.getName().equals(name) && descriptor.getReadMethod() != null) {
                    var method = descriptor.getReadMethod();
                    method.setAccessible(true);
                    return method.invoke(bean);
                }
            }
            if (bean.getClass().isRecord()) {
                var method = bean.getClass().getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(bean);
            }
            Field field = findField(bean.getClass(), name);
            field.setAccessible(true);
            return field.get(bean);
        } catch (ReflectiveOperationException | java.beans.IntrospectionException exception) {
            throw new DictionaryMappingException("Невозможно прочитать поле '%s'.".formatted(name), exception);
        }
    }

    /** Устанавливает поле сущности через сеттер либо непосредственно в поле. */
    public static void write(Object bean, String name, Object value) {
        try {
            for (var descriptor : Introspector.getBeanInfo(bean.getClass()).getPropertyDescriptors()) {
                if (descriptor.getName().equals(name) && descriptor.getWriteMethod() != null) {
                    var method = descriptor.getWriteMethod();
                    method.setAccessible(true);
                    method.invoke(bean, value);
                    return;
                }
            }
            Field field = findField(bean.getClass(), name);
            if (Modifier.isFinal(field.getModifiers())) {
                throw new DictionaryMappingException(
                        "Невозможно изменить неизменяемое поле сущности '%s'.".formatted(name));
            }
            field.setAccessible(true);
            field.set(bean, value);
        } catch (ReflectiveOperationException | java.beans.IntrospectionException exception) {
            throw new DictionaryMappingException("Невозможно записать поле '%s'.".formatted(name), exception);
        }
    }

    /** Создаёт DTO-record либо обычный DTO с конструктором без аргументов. */
    public static <T> T newDto(Class<T> dtoClass, Map<String, Object> values) {
        try {
            if (dtoClass.isRecord()) {
                RecordComponent[] components = dtoClass.getRecordComponents();
                Class<?>[] types = new Class<?>[components.length];
                Object[] args = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    types[i] = components[i].getType();
                    args[i] = values.get(components[i].getName());
                }
                Constructor<T> constructor = dtoClass.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            }
            T dto = instantiate(dtoClass);
            values.forEach((name, value) -> write(dto, name, value));
            return dto;
        } catch (ReflectiveOperationException exception) {
            throw new DictionaryMappingException(
                    "Не удалось создать DTO '%s'.".formatted(dtoClass.getName()), exception);
        }
    }

    /** Создаёт экземпляр сущности или встраиваемого объекта. */
    public static <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new DictionaryMappingException(
                    "Тип '%s' должен иметь конструктор без аргументов."
                            .formatted(type.getName()), exception);
        }
    }

    /** Возвращает имя и аннотацию JPA-поля, в том числе при доступе через геттер. */
    public static boolean hasGeneratedId(Class<?> entityClass, String name) {
        try {
            Field field = findField(entityClass, name);
            if (field.isAnnotationPresent(jakarta.persistence.GeneratedValue.class)) return true;
        } catch (NoSuchFieldException ignored) {
            // JPA допускает доступ через методы вместо полей.
        }
        try {
            for (var descriptor : Introspector.getBeanInfo(entityClass).getPropertyDescriptors()) {
                if (descriptor.getName().equals(name) && descriptor.getReadMethod() != null) {
                    return descriptor.getReadMethod()
                            .isAnnotationPresent(jakarta.persistence.GeneratedValue.class);
                }
            }
        } catch (java.beans.IntrospectionException exception) {
            throw new DictionaryMappingException("Не удалось прочитать JavaBeans-метаданные.", exception);
        }
        return false;
    }

    /** Проверяет, не является ли коллекционная связь обратной стороной. */
    public static boolean inverseAssociation(Class<?> entityClass, String name) {
        try {
            Field field = findField(entityClass, name);
            var oneToOne = field.getAnnotation(jakarta.persistence.OneToOne.class);
            if (oneToOne != null && !oneToOne.mappedBy().isBlank()) return true;
            var oneToMany = field.getAnnotation(jakarta.persistence.OneToMany.class);
            if (oneToMany != null && !oneToMany.mappedBy().isBlank()) return true;
            var manyToMany = field.getAnnotation(jakarta.persistence.ManyToMany.class);
            if (manyToMany != null && !manyToMany.mappedBy().isBlank()) return true;
        } catch (NoSuchFieldException ignored) { }
        try {
            for (var descriptor : Introspector.getBeanInfo(entityClass).getPropertyDescriptors()) {
                if (!descriptor.getName().equals(name) || descriptor.getReadMethod() == null) continue;
                var getter = descriptor.getReadMethod();
                var oneToOne = getter.getAnnotation(jakarta.persistence.OneToOne.class);
                if (oneToOne != null && !oneToOne.mappedBy().isBlank()) return true;
                var oneToMany = getter.getAnnotation(jakarta.persistence.OneToMany.class);
                if (oneToMany != null && !oneToMany.mappedBy().isBlank()) return true;
                var manyToMany = getter.getAnnotation(jakarta.persistence.ManyToMany.class);
                return manyToMany != null && !manyToMany.mappedBy().isBlank();
            }
        } catch (java.beans.IntrospectionException exception) {
            throw new DictionaryMappingException("Не удалось прочитать JavaBeans-метаданные.", exception);
        }
        return false;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field field = fieldOrNull(current, name);
            if (field != null) return field;
        }
        throw new NoSuchFieldException(name);
    }

    private static Field fieldOrNull(Class<?> type, String name) {
        try { return type.getDeclaredField(name); }
        catch (NoSuchFieldException ignored) { return null; }
    }
}
