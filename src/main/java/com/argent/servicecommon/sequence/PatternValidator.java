package com.argent.servicecommon.sequence;

import java.util.IllegalFormatException;
import java.util.regex.Pattern;

/**
 * Проверка шаблона номера документа.
 *
 * <p>Шаблон форматируется {@link String#format} с аргументами {@code (ключ периода, счётчик)}:
 * {@code %1$s} — период, {@code %2$d} — счётчик, например {@code ORD-%1$s-%2$05d}. Шаблон
 * допустим, если пробное форматирование проходит и счётчик ({@code %2$d} или второй
 * последовательный {@code %d}) в нём присутствует.</p>
 */
public final class PatternValidator {

    /** Подстановка счётчика: {@code %2$d} или {@code %d} с необязательными флагами и шириной. */
    private static final Pattern COUNTER = Pattern.compile("%(?:2\\$)?[-#+ 0,(]*\\d*d");

    private static final String SAMPLE_PERIOD = "2026-W01";
    private static final long SAMPLE_COUNTER = 1L;

    private PatternValidator() {
    }

    /**
     * Проверяет шаблон номера.
     *
     * @param pattern шаблон
     * @throws IllegalArgumentException если шаблон не форматируется или не содержит счётчика
     */
    public static void validate(String pattern) {
        if (!isValid(pattern)) {
            throw new IllegalArgumentException("Недопустимый шаблон номера: " + pattern);
        }
    }

    /**
     * Проверяет шаблон номера без исключения.
     *
     * @param pattern шаблон
     * @return {@code true}, если шаблон допустим
     */
    public static boolean isValid(String pattern) {
        if (pattern == null || !COUNTER.matcher(pattern.replace("%%", "")).find()) {
            return false;
        }
        try {
            // Значение счётчика обязано влиять на результат форматирования.
            String sample = String.format(pattern, SAMPLE_PERIOD, SAMPLE_COUNTER);
            String withOtherCounter = String.format(pattern, SAMPLE_PERIOD, SAMPLE_COUNTER + 1);
            return !sample.equals(withOtherCounter);
        } catch (IllegalFormatException exception) {
            return false;
        }
    }
}
