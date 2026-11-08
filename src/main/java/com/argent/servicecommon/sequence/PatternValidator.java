package com.argent.servicecommon.sequence;

import java.util.IllegalFormatException;
import java.util.regex.Pattern;

/**
 * Проверка шаблона номера пробным {@link String#format} с аргументами {@code (ключ периода, счётчик)}: шаблон
 * должен форматироваться и содержать счётчик ({@code %2$d} или второй последовательный {@code %d}).
 */
public final class PatternValidator {

    /** Подстановка счётчика: {@code %2$d} или {@code %d} с необязательными флагами и шириной. */
    private static final Pattern COUNTER = Pattern.compile("%(?:2\\$)?[-#+ 0,(]*\\d*d");

    private static final String SAMPLE_PERIOD = "2026-W01";
    private static final long SAMPLE_COUNTER = 1L;

    private PatternValidator() {
    }

    /** @throws IllegalArgumentException шаблон не форматируется или не содержит счётчика */
    public static void validate(String pattern) {
        if (!isValid(pattern)) {
            throw new IllegalArgumentException("Недопустимый шаблон номера: " + pattern);
        }
    }

    /** То же без исключения. */
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
