package com.argent.servicecommon.transfer.imp;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicFieldDefinition;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicOption;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicReferenceDefinition;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Преобразует строку ячейки в значение типа поля DTO.
 *
 * <ul>
 *     <li>Числа: русская ({@code 1 234,50}) и английская ({@code 1,234.50}) запись. Пробелы, в том числе
 *     неразрывные, удаляются. Если есть и точка, и запятая, десятичным разделителем считается последний
 *     знак. Одна запятая — десятичный разделитель ({@code 1,5}), кроме целых полей с группами по три
 *     цифры ({@code 1,234}).</li>
 *     <li>Логические: {@code да/нет}, {@code true/false}, {@code 1/0}, {@code yes/no}.</li>
 *     <li>Даты: {@code дд.мм.гггг} и ISO; дата-время — также {@code дд.мм.гггг чч:мм[:сс]}.</li>
 *     <li>Перечисления: имя константы без учёта регистра.</li>
 *     <li>Ссылка на справочник: точное совпадение подписи (поле отображения целевого справочника) без
 *     учёта регистра; результат — {@code Map} с идентификатором и подписью.</li>
 * </ul>
 *
 * <p>Пустая строка даёт {@code null}. Ошибка преобразования — {@link IllegalArgumentException}
 * с сообщением для пользователя.</p>
 */
public class ValueConverter {

    private static final int OPTIONS_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 200;
    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter RU_DATE_TIME =
            DateTimeFormatter.ofPattern("d.M.uuuu H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm[:ss]");

    private final DynamicDictionaryQueryService queries;

    public ValueConverter(DynamicDictionaryQueryService queries) {
        this.queries = Objects.requireNonNull(queries);
    }

    /** Преобразует значение без кэша ссылок. */
    public Object convert(DynamicFieldDefinition field, Class<?> javaType, String raw) {
        return convert(field, javaType, raw, new HashMap<>());
    }

    /**
     * Преобразует значение; найденные ссылки кэшируются в {@code referenceCache} в пределах импорта.
     *
     * @param field          описание поля из схемы справочника
     * @param javaType       тип свойства DTO
     * @param raw            строка ячейки
     * @param referenceCache кэш «справочник + подпись → ссылка»
     */
    public Object convert(DynamicFieldDefinition field, Class<?> javaType, String raw,
                          Map<String, Map<String, Object>> referenceCache) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip();
        DynamicReferenceDefinition reference = field.reference();
        if (reference != null) {
            if (reference.multiple()) {
                throw new IllegalArgumentException("Тип поля не поддерживается импортом");
            }
            return referenceCache.computeIfAbsent(reference.dictionaryCode() + "\u0000" + value.toLowerCase(Locale.ROOT),
                    key -> resolveReference(reference, value));
        }
        if ("array".equals(field.type()) || "object".equals(field.type())) {
            throw new IllegalArgumentException("Тип поля не поддерживается импортом");
        }
        return convertScalar(javaType, value);
    }

    /** Преобразует строку в простой тип (строка, число, логический, дата, UUID, перечисление). */
    public Object convertScalar(Class<?> type, String value) {
        Class<?> target = boxed(type);
        if (target == String.class) return value;
        if (target == Boolean.class) return parseBoolean(value);
        if (target == Integer.class || target == Long.class || target == Short.class
                || target == Byte.class || target == BigInteger.class) {
            return integer(target, value);
        }
        if (target == BigDecimal.class) return number(value, false);
        if (target == Double.class) return number(value, false).doubleValue();
        if (target == Float.class) return number(value, false).floatValue();
        if (target == LocalDate.class) return parseDate(value);
        if (target == LocalDateTime.class) return parseDateTime(value);
        if (target == LocalTime.class) return parseTime(value);
        if (target == Instant.class) return zoned(value).toInstant();
        if (target == OffsetDateTime.class) return zoned(value).toOffsetDateTime();
        if (target == ZonedDateTime.class) return zoned(value);
        if (target == UUID.class) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Некорректный идентификатор: " + value);
            }
        }
        if (target.isEnum()) {
            for (Object constant : target.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(value)) return constant;
            }
            throw new IllegalArgumentException("Неизвестное значение: " + value);
        }
        throw new IllegalArgumentException("Тип поля не поддерживается импортом");
    }

    private Map<String, Object> resolveReference(DynamicReferenceDefinition reference, String label) {
        String notFound = "Не найдена запись справочника %s: %s".formatted(reference.dictionaryCode(), label);
        if (label.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException(notFound);
        }
        // Поиск options ищет подстроку во всех строковых полях, поэтому точное совпадение подписи
        // проверяется здесь; страниц может быть несколько.
        List<DynamicOption> matches = new ArrayList<>();
        for (int page = 0; ; page++) {
            DynamicPage<DynamicOption> options =
                    queries.options(reference.dictionaryCode(), page, OPTIONS_PAGE_SIZE, label);
            for (DynamicOption option : options.items()) {
                if (option.label() != null && option.label().strip().equalsIgnoreCase(label)) {
                    matches.add(option);
                }
            }
            if (options.last() || options.items().isEmpty() || matches.size() > 1) break;
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(notFound);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Найдено несколько записей справочника %s: %s".formatted(reference.dictionaryCode(), label));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(reference.idField(), matches.getFirst().id());
        if (reference.displayField() != null) {
            result.put(reference.displayField(), matches.getFirst().label());
        }
        return Map.copyOf(result);
    }

    private static Object integer(Class<?> target, String value) {
        BigDecimal number = number(value, true).stripTrailingZeros();
        if (number.scale() > 0) {
            throw new IllegalArgumentException("Ожидается целое число: " + value);
        }
        try {
            if (target == Integer.class) return number.intValueExact();
            if (target == Long.class) return number.longValueExact();
            if (target == Short.class) return number.shortValueExact();
            if (target == Byte.class) return number.byteValueExact();
            return number.toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Число вне допустимого диапазона: " + value);
        }
    }

    private static BigDecimal number(String value, boolean integer) {
        String text = value.replaceAll("[\\s\\u00A0\\u2007\\u202F']", "");
        int comma = text.lastIndexOf(',');
        int dot = text.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            text = comma > dot ? text.replace(".", "").replace(',', '.') : text.replace(",", "");
        } else if (comma >= 0) {
            boolean grouping = text.indexOf(',') != comma
                    || integer && text.matches("[-+]?\\d{1,3}(,\\d{3})+");
            text = grouping ? text.replace(",", "") : text.replace(',', '.');
        } else if (dot >= 0 && text.indexOf('.') != dot) {
            // Несколько точек — разделители групп разрядов (1.234.567).
            text = text.replace(".", "");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Некорректное число: " + value);
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "да", "true", "1", "yes", "истина" -> true;
            case "нет", "false", "0", "no", "ложь" -> false;
            default -> throw new IllegalArgumentException(
                    "Некорректное логическое значение: " + value + ", ожидается да/нет");
        };
    }

    private static LocalDate parseDate(String value) {
        try {
            if (value.contains(".")) return LocalDate.parse(value, RU_DATE);
            if (value.contains("T") || value.contains(" ")) return LocalDateTime.parse(value.replace(' ', 'T')).toLocalDate();
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Некорректная дата: " + value + ", ожидается дд.мм.гггг или гггг-мм-дд");
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        try {
            if (value.contains(".") && value.indexOf('.') < 3) {
                return value.contains(":") ? LocalDateTime.parse(value, RU_DATE_TIME)
                        : LocalDate.parse(value, RU_DATE).atStartOfDay();
            }
            if (value.length() <= 10) return LocalDate.parse(value).atStartOfDay();
            return LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Некорректные дата и время: " + value + ", ожидается дд.мм.гггг чч:мм или ISO");
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value, TIME);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Некорректное время: " + value + ", ожидается чч:мм");
        }
    }

    private static ZonedDateTime zoned(String value) {
        try {
            return ZonedDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return parseDateTime(value).atZone(ZoneId.systemDefault());
        }
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }
}
