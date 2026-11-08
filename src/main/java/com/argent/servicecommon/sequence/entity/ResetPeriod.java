package com.argent.servicecommon.sequence.entity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;

/** Период сброса счётчика: ключ периода меняется ровно тогда, когда счётчик начинается заново с 1. */
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
     * Ключ периода: DAILY — {@code yyyyMMdd}, MONTHLY — {@code yyyy-MM}, YEARLY — {@code yyyy}, WEEKLY — ISO-неделя
     * {@code <год недели>-W<две цифры>} (2025-12-29 → {@code 2026-W01}), NEVER — постоянный {@code 1}.
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
