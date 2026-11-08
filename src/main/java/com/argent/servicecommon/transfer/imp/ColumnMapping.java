package com.argent.servicecommon.transfer.imp;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySchema;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicFieldDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Сопоставление заголовков листа с полями справочника и ключевые поля для UPSERT.
 *
 * <p>Заголовок, отсутствующий в {@code headerToField}, считается несопоставленным: импорт сообщает
 * «Колонка не сопоставлена с полем: …» (при {@code fail-all} лист не импортируется). Заголовок со
 * значением {@code null} или пустой строкой явно игнорируется. Пустой {@code keyFields} означает
 * ключ по умолчанию из аннотаций сущности (см. {@link EntityKeyFields}).</p>
 *
 * @param headerToField заголовок → имя поля DTO
 * @param keyFields     ключевые поля для UPSERT
 */
public record ColumnMapping(Map<String, String> headerToField, List<String> keyFields) {

    public ColumnMapping {
        headerToField = headerToField == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headerToField));
        keyFields = keyFields == null ? List.of() : keyFields.stream()
                .filter(field -> field != null && !field.isBlank()).map(String::strip).toList();
    }

    /**
     * Строит сопоставление по подписи ({@code label}) или имени поля без учёта регистра, пробелов и
     * подчёркиваний; подпись имеет приоритет. Ключевые поля не задаются.
     */
    public static ColumnMapping auto(DynamicDictionarySchema schema, List<String> headers) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String header : headers) {
            String key = normalize(header);
            if (key.isEmpty()) continue;
            String field = schema.fields().stream()
                    .filter(candidate -> key.equals(normalize(candidate.label())))
                    .map(DynamicFieldDefinition::name).findFirst()
                    .or(() -> schema.fields().stream()
                            .filter(candidate -> key.equals(normalize(candidate.name())))
                            .map(DynamicFieldDefinition::name).findFirst())
                    .orElse(null);
            if (field != null) {
                mapping.put(header, field);
            }
        }
        return new ColumnMapping(mapping, List.of());
    }

    /** Копия с другими ключевыми полями. */
    public ColumnMapping withKeyFields(List<String> fields) {
        return new ColumnMapping(headerToField, fields);
    }

    /** Нижний регистр без пробелов и подчёркиваний, «ё» → «е». */
    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[\\s_]+", "");
    }
}
