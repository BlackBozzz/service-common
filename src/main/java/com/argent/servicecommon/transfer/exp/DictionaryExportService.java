package com.argent.servicecommon.transfer.exp;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySchema;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicFieldDefinition;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;
import com.argent.servicecommon.dictionary.mapper.DictionaryProperties;
import com.argent.servicecommon.transfer.exp.TableData.Column;
import com.argent.servicecommon.transfer.exp.TableData.ColumnType;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Строит {@link TableData} из справочника: видимые поля по {@code order}, ссылки — поле отображения, коллекции и
 * {@code byte[]} пропускаются; записи читаются страницами с поиском и сортировкой универсальной таблицы.
 */
public class DictionaryExportService {

    /** Размер страницы чтения: предел {@code DynamicDictionaryQueryService.page}. */
    static final int PAGE_SIZE = 100;

    private final DynamicDictionaryCatalog catalog;
    private final DynamicDictionarySchemaService schemas;
    private final DynamicDictionaryQueryService queries;
    private final int maxRows;

    public DictionaryExportService(DynamicDictionaryCatalog catalog, DynamicDictionarySchemaService schemas,
                                   DynamicDictionaryQueryService queries, int maxRows) {
        this.catalog = Objects.requireNonNull(catalog);
        this.schemas = Objects.requireNonNull(schemas);
        this.queries = Objects.requireNonNull(queries);
        this.maxRows = maxRows;
    }

    /**
     * Весь справочник в таблицу; {@code search}/{@code sort} ({@code поле,asc|desc}) как в универсальной таблице.
     *
     * @throws IllegalArgumentException записей больше {@code maxRows} (400)
     */
    @Transactional(readOnly = true)
    public TableData build(String code, String search, String sort) {
        Class<?> dtoClass = catalog.require(code).dtoClass();
        DynamicDictionarySchema schema = schemas.describe(code);
        Map<String, Class<?>> propertyTypes = new HashMap<>();
        Map<String, Integer> declared = new HashMap<>();
        for (DictionaryProperties.Property property : DictionaryProperties.dtoProperties(dtoClass)) {
            propertyTypes.put(property.name(), property.type());
            declared.put(property.name(), declared.size());
        }
        // Порядок формы: order из @DictionaryField, затем порядок объявления в DTO.
        List<DynamicFieldDefinition> ordered = schema.fields().stream()
                .sorted(Comparator.comparingInt(DynamicFieldDefinition::order)
                        .thenComparingInt(field -> declared.getOrDefault(field.name(), Integer.MAX_VALUE)))
                .toList();

        List<Exported> fields = new ArrayList<>();
        for (DynamicFieldDefinition field : ordered) {
            Class<?> type = propertyTypes.get(field.name());
            if (!field.visible() || type == null || !exportable(field, type)) continue;
            ColumnType columnType = field.reference() != null ? ColumnType.TEXT : columnType(type);
            String label = field.label() == null || field.label().isBlank() ? field.name() : field.label();
            fields.add(new Exported(new Column(field.name(), label, columnType), field));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            DynamicPage<?> result = queries.page(code, page, PAGE_SIZE, blankToNull(sort), blankToNull(search),
                    Map.of());
            if (rows.size() + result.items().size() > maxRows) {
                throw new IllegalArgumentException("Слишком много записей для экспорта: больше " + maxRows);
            }
            for (Object item : result.items()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Exported exported : fields) {
                    row.put(exported.column().key(), value(item, exported.field()));
                }
                rows.add(row);
            }
            if (result.last() || result.items().isEmpty()) break;
        }
        String title = schema.name() == null || schema.name().isBlank() ? code : schema.name();
        return new TableData(title, fields.stream().map(Exported::column).toList(), rows);
    }

    private static boolean exportable(DynamicFieldDefinition field, Class<?> type) {
        if (field.reference() != null) return !field.reference().multiple();
        if ("array".equals(field.type()) || "object".equals(field.type())) return false;
        return !type.isArray() && !Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type);
    }

    private static ColumnType columnType(Class<?> type) {
        if (type == LocalDate.class) return ColumnType.DATE;
        if (type == LocalDateTime.class || type == Instant.class || type == OffsetDateTime.class
                || type == ZonedDateTime.class) return ColumnType.DATETIME;
        if (type == Boolean.class || type == boolean.class) return ColumnType.BOOLEAN;
        if (Number.class.isAssignableFrom(type) || type == int.class || type == long.class || type == short.class
                || type == byte.class || type == double.class || type == float.class) return ColumnType.NUMBER;
        return ColumnType.TEXT;
    }

    private static Object value(Object item, DynamicFieldDefinition field) {
        Object value = DictionaryProperties.read(item, field.name());
        if (value == null) return null;
        if (field.reference() != null) {
            String display = field.reference().displayField() != null
                    ? field.reference().displayField() : field.reference().idField();
            Object shown = DictionaryProperties.read(value, display);
            return shown == null ? null : String.valueOf(shown);
        }
        return value instanceof Enum<?> constant ? constant.name() : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Колонка таблицы и поле схемы, из которого берётся значение. */
    private record Exported(Column column, DynamicFieldDefinition field) {
    }
}
