package com.argent.servicecommon.sequence.entity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;

/**
 * Период сброса счётчика номеров.
 *
 * <p>{@link #periodKey(LocalDate)} возвращает ключ периода: строку, которая меняется ровно тогда,
 * когда счётчик должен начаться заново с 1.</p>
 */
public enum ResetPeriod {
    /** Счётчик не сбрасывается. */
    NEVER,
    /** Сброс в начале календарного года. */
    YEARLY,
    /** Сброс в начале месяца. */
    MONTHLY,
    /** Сброс в начале ISO-недели. */
    WEEKLY,
    /** Сброс в начале суток. */
    DAILY;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Ключ периода для даты.
     *
     * <p>DAILY — {@code yyyyMMdd}, MONTHLY — {@code yyyy-MM}, YEARLY — {@code yyyy}, WEEKLY —
     * ISO-неделя {@code <год недели>-W<номер недели из двух цифр>} (2025-12-29 → {@code 2026-W01}),
     * NEVER — постоянный ключ {@code 1}.</p>
     *
     * @param date текущая дата
     * @return ключ периода
     */
    public String periodKey(LocalDate date) {
        return switch (this) {
            case NEVER -> "1";
            case YEARLY -> String.valueOf(date.getYear());
            case MONTHLY -> date.format(MONTH);
            case WEEKLY -> "%d-W%02d".formatted(
                    date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case DAILY -> date.format(DateTimeFormatter.BASIC_ISO_DATE);
        };
    }
}
