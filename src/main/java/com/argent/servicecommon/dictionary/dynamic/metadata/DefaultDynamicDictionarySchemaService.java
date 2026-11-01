package com.argent.servicecommon.dictionary.dynamic.metadata;

import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicDictionaryDisplay;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicFormField;
import com.argent.servicecommon.dictionary.dynamic.dto.*;
import com.argent.servicecommon.dictionary.mapper.DictionaryProperties;
import com.argent.servicecommon.dictionary.record.DictionaryFieldDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.PluralAttribute;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Дополняет существующие метаданные DTO параметрами динамической формы.
 * Повторно не реализует извлечение Bean Validation и подписей полей.
 */
public class DefaultDynamicDictionarySchemaService implements DynamicDictionarySchemaService {
    private final DynamicDictionaryCatalog catalog;
    private final EntityManager entityManager;

    public DefaultDynamicDictionarySchemaService(DynamicDictionaryCatalog catalog,
                                                 EntityManager entityManager) {
        this.catalog = Objects.requireNonNull(catalog);
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    public DynamicDictionarySchema describe(String code) {
        RegisteredDictionary dictionary = catalog.require(code);
        String idField = idField(dictionary.entityClass());
        // Без первичного ключа в DTO невозможно вернуть ID выбранной записи.
        if (DictionaryProperties.dtoProperties(dictionary.dtoClass()).stream()
                .noneMatch(property -> property.name().equals(idField))) {
            throw new IllegalStateException(
                    "DTO справочника '%s' должен содержать поле идентификатора '%s'."
                            .formatted(code, idField));
        }
        String displayField = displayField(dictionary.dtoClass());
        return new DynamicDictionarySchema(
                dictionary.definition().code(), dictionary.definition().name(),
                idField, displayField,
                enrich(dictionary.entityClass(), dictionary.dtoClass(), dictionary.definition().fields()));
    }

    /** Находит название JPA-идентификатора без соглашения о названии id. */
    public String idField(Class<?> entityClass) {
        var entityType = entityManager.getMetamodel().entity(entityClass);
        if (!entityType.hasSingleIdAttribute()) {
            throw new IllegalStateException(
                    "Для справочника требуется единственный простой первичный ключ: "
                            + entityClass.getName());
        }
        return entityType.getSingularAttributes().stream()
                .filter(attribute -> attribute.isId())
                .map(Attribute::getName).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "У сущности '%s' нет первичного ключа.".formatted(entityClass.getName())));
    }

    /**
     * Выбирает текстовое поле отображения: явная аннотация, name, title, code
     * либо значение первичного ключа (если строковых полей нет).
     */
    public String displayField(Class<?> dtoClass) {
        var properties = DictionaryProperties.dtoProperties(dtoClass);
        List<String> explicit = properties.stream()
                .filter(field -> hasDisplayAnnotation(dtoClass, field.name()))
                .map(DictionaryProperties.Property::name).toList();
        if (explicit.size() > 1) {
            throw new IllegalStateException(
                    "В DTO '%s' указано несколько полей отображения.".formatted(dtoClass.getName()));
        }
        if (!explicit.isEmpty()) {
            String selected = explicit.getFirst();
            var field = properties.stream().filter(item -> item.name().equals(selected)).findFirst().orElseThrow();
            if (field.type() != String.class) {
                throw new IllegalStateException("Поле отображения '%s' должно иметь тип String."
                        .formatted(selected));
            }
            return selected;
        }
        for (String name : List.of("name", "title", "code")) {
            if (properties.stream().anyMatch(item -> item.name().equals(name)
                    && item.type() == String.class && item.visible())) return name;
        }
        return null;
    }

    private List<DynamicFieldDefinition> enrich(Class<?> entityClass, Class<?> dtoClass,
                                                 List<DictionaryFieldDefinition> fields) {
        List<DynamicFieldDefinition> result = new ArrayList<>();
        ManagedType<?> model = entityManager.getMetamodel().managedType(entityClass);
        for (DictionaryFieldDefinition field : fields) {
            Attribute<?, ?> attribute = model.getAttribute(field.name());
            var property = DictionaryProperties.dtoProperties(dtoClass).stream()
                    .filter(item -> item.name().equals(field.name())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Поле DTO не обнаружено: " + field.name()));
            Class<?> dtoValueType = attribute.isCollection()
                    ? DictionaryProperties.elementClass(property) : property.type();
            Class<?> entityValueType = attribute.isCollection()
                    ? ((PluralAttribute<?, ?, ?>) attribute).getElementType().getJavaType()
                    : attribute.getJavaType();
            DynamicFormField options = fieldOptions(dtoClass, field.name());
            String type = preciseType(field.type(), dtoValueType);
            DynamicReferenceDefinition reference = null;
            if (field.referenceCode() != null) {
                // Проверяем связанные справочники через тот же реестр.
                RegisteredDictionary target = catalog.require(field.referenceCode());
                reference = new DynamicReferenceDefinition(field.referenceCode(),
                        idField(target.entityClass()), displayField(target.dtoClass()), attribute.isCollection());
            }
            List<DynamicFieldDefinition> nested = field.fields().isEmpty()
                    ? List.of() : enrich(entityValueType, dtoValueType, field.fields());
            Integer minLength = null, maxLength = null, minItems = null, maxItems = null;
            String minimum = null, maximum = null, pattern = null;
            for (var rule : field.validation()) {
                var args = rule.attributes();
                switch (rule.name()) {
                    case "Size" -> {
                        if (property.type() == String.class) {
                            minLength = integer(args.get("min")); maxLength = integer(args.get("max"));
                        } else if (attribute.isCollection()) {
                            minItems = integer(args.get("min")); maxItems = integer(args.get("max"));
                        }
                    }
                    case "Min", "DecimalMin" -> minimum = String.valueOf(args.get("value"));
                    case "Max", "DecimalMax" -> maximum = String.valueOf(args.get("value"));
                    case "Pattern" -> pattern = String.valueOf(args.get("regexp"));
                    default -> { /* Остальные ограничения остаются в validation. */ }
                }
            }
            String control = options != null && options.control() != DynamicFormField.Control.AUTO
                    ? options.control().name().toLowerCase(java.util.Locale.ROOT)
                    : defaultControl(field, type, reference);
            result.add(new DynamicFieldDefinition(
                    field.name(), field.label(), field.description(), type, control,
                    options == null ? "" : options.placeholder(),
                    options == null ? "" : options.help(), field.order(), field.visible(),
                    field.readOnly(), field.required(), minLength, maxLength, minItems, maxItems,
                    minimum, maximum, pattern, field.enumValues(), reference, nested,
                    field.validation(), field.elementValidation()));
        }
        return List.copyOf(result);
    }

    private static String defaultControl(DictionaryFieldDefinition field, String type,
                                         DynamicReferenceDefinition reference) {
        if (!field.visible()) return "hidden";
        if (reference != null) return reference.multiple() ? "multi_reference" : "reference";
        return switch (type) {
            case "boolean" -> "checkbox";
            case "date" -> "date";
            case "time" -> "time";
            case "datetime" -> "datetime";
            case "number", "integer", "decimal" -> "number";
            case "enum" -> "select";
            default -> "text";
        };
    }

    private static String preciseType(String existing, Class<?> type) {
        if (existing.equals("array") || existing.equals("reference") || existing.equals("object")) return existing;
        if (type == java.time.LocalDate.class) return "date";
        if (type == java.time.LocalTime.class) return "time";
        if (type == java.time.LocalDateTime.class || type == java.time.Instant.class
                || type == java.time.OffsetDateTime.class || type == java.time.ZonedDateTime.class) return "datetime";
        if (type == java.math.BigDecimal.class || type == double.class || type == Double.class
                || type == Float.class || type == float.class) return "decimal";
        if (type == java.util.UUID.class) return "uuid";
        if (type.isEnum()) return "enum";
        if (Number.class.isAssignableFrom(type) || type == int.class || type == long.class
                || type == short.class || type == byte.class) return "integer";
        return existing;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static boolean hasDisplayAnnotation(Class<?> type, String name) {
        return annotation(type, name, DynamicDictionaryDisplay.class) != null;
    }

    private static DynamicFormField fieldOptions(Class<?> type, String name) {
        return annotation(type, name, DynamicFormField.class);
    }

    /** Находит аннотацию на record-компоненте или объявленном поле DTO. */
    private static <A extends java.lang.annotation.Annotation> A annotation(
            Class<?> type, String name, Class<A> annotationType) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(name)) {
                    A value = component.getAnnotation(annotationType);
                    if (value != null) return value;
                }
            }
        }
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                return field.getAnnotation(annotationType);
            } catch (NoSuchFieldException ignored) {
                // Поле может находиться в родительском классе.
            }
        }
        return null;
    }
}
