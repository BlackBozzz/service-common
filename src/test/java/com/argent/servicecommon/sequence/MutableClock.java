package com.argent.servicecommon.sequence;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Тестовые часы, время которых можно переставлять между вызовами. */
public final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** Часы UTC, указывающие на полдень заданной даты. */
    public static MutableClock at(LocalDate date) {
        return new MutableClock(date.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    /** Переставляет часы на полдень заданной даты. */
    public void set(LocalDate date) {
        this.instant = date.atTime(12, 0).atZone(zone).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
