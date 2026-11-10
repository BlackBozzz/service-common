package com.argent.servicecommon.report;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Приведение значений к типам параметров и полей отчёта (строки, числа, даты, enum, UUID).
 * Неприводимое значение даёт {@link IllegalArgumentException}; вызывающий добавляет имя параметра или поля.
 */
final class ReportValues {

    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter RU_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm[:ss]");

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            int.class, Integer.class,
            long.class, Long.class,
            short.class, Short.class,
            double.class, Double.class,
            float.class, Float.class,
            boolean.class, Boolean.class);

    private ReportValues() {
    }

    /** Приводит значение к целевому типу; {@code null} остаётся {@code null}. */
    static Object convert(Object value, Class<?> target) {
        if (value == null) {
            return null;
        }
        Class<?> type = target.isPrimitive() ? WRAPPERS.getOrDefault(target, target) : target;
        if (type.isInstance(value)) {
            return value;
        }
        try {
            Object result = convertKnown(value, type);
            if (result != null) {
                return result;
            }
        } catch (RuntimeException e) {
            throw incompatible(value, target);
        }
        throw incompatible(value, target);
    }

    private static Object convertKnown(Object value, Class<?> type) {
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (Number.class.isAssignableFrom(type)) {
            if (value instanceof Number number) {
                return toNumber(new BigDecimal(number.toString()), type);
            }
            if (value instanceof String text) {
                return toNumber(new BigDecimal(text.trim()), type);
            }
            return null;
        }
        if (type == Boolean.class) {
            return value instanceof String text ? toBoolean(text) : null;
        }
        if (type == UUID.class) {
            return value instanceof String text ? UUID.fromString(text.trim()) : null;
        }
        if (type.isEnum()) {
            return value instanceof String text ? toEnum(text.trim(), type) : null;
        }
        if (type == LocalDate.class) {
            return toLocalDate(value);
        }
        if (type == LocalDateTime.class) {
            return toLocalDateTime(value);
        }
        if (type == Instant.class) {
            return toInstant(value);
        }
        if (Date.class.isAssignableFrom(type)) {
            return toDate(value, type);
        }
        return null;
    }

    private static Object toNumber(BigDecimal number, Class<?> type) {
        if (type == BigDecimal.class || type == Number.class) {
            return number;
        }
        if (type == BigInteger.class) {
            return number.toBigIntegerExact();
        }
        if (type == Integer.class) {
            return number.intValueExact();
        }
        if (type == Long.class) {
            return number.longValueExact();
        }
        if (type == Short.class) {
            return number.shortValueExact();
        }
        if (type == Double.class) {
            return finite(number.doubleValue());
        }
        if (type == Float.class) {
            return (float) finite(number.floatValue());
        }
        return null;
    }

    /** Переполнение {@code double}/{@code float} — ошибка, а не бесконечность. */
    private static double finite(double value) {
        if (Double.isInfinite(value)) {
            throw new ArithmeticException("Переполнение");
        }
        return value;
    }

    private static Boolean toBoolean(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "true", "да", "1" -> Boolean.TRUE;
            case "false", "нет", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Object toEnum(String text, Class<?> type) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(text)) {
                return constant;
            }
        }
        return null;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof String text) {
            return parseLocalDate(text.trim());
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        Instant instant = temporalInstant(value);
        return instant == null ? null : instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof String text) {
            return parseLocalDateTime(text.trim());
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        Instant instant = temporalInstant(value);
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static Instant toInstant(Object value) {
        if (value instanceof String text) {
            return Instant.parse(text.trim());
        }
        return temporalInstant(value);
    }

    /** Момент времени для {@link Instant}, {@link Date}, {@link LocalDate} и {@link LocalDateTime} (системная зона). */
    private static Instant temporalInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime());
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        return null;
    }

    private static Object toDate(Object value, Class<?> type) {
        Instant instant;
        if (value instanceof String text) {
            String trimmed = text.trim();
            LocalDateTime dateTime;
            try {
                dateTime = parseLocalDateTime(trimmed);
            } catch (DateTimeParseException e) {
                dateTime = parseLocalDate(trimmed).atStartOfDay();
            }
            instant = dateTime.atZone(ZoneId.systemDefault()).toInstant();
        } else {
            instant = temporalInstant(value);
        }
        if (instant == null) {
            return null;
        }
        if (type == java.sql.Date.class) {
            return new java.sql.Date(instant.toEpochMilli());
        }
        if (type == Timestamp.class) {
            return Timestamp.from(instant);
        }
        return type == Date.class ? Date.from(instant) : null;
    }

    private static LocalDate parseLocalDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(text, RU_DATE);
        }
    }

    private static LocalDateTime parseLocalDateTime(String text) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(text, RU_DATE_TIME);
        }
    }

    private static IllegalArgumentException incompatible(Object value, Class<?> target) {
        return new IllegalArgumentException("Значение типа " + value.getClass().getSimpleName()
                + " не приводится к " + target.getSimpleName());
    }
}
