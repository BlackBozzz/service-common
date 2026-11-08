package com.argent.servicecommon.sequence.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Ключи периодов сброса счётчика, включая границы ISO-недель. */
class ResetPeriodTest {

    @Test
    void shouldFormatEachPeriod() {
        LocalDate date = LocalDate.of(2026, 11, 7);
        assertThat(ResetPeriod.DAILY.periodKey(date)).isEqualTo("20261107");
        assertThat(ResetPeriod.WEEKLY.periodKey(date)).isEqualTo("2026-W45");
        assertThat(ResetPeriod.MONTHLY.periodKey(date)).isEqualTo("2026-11");
        assertThat(ResetPeriod.YEARLY.periodKey(date)).isEqualTo("2026");
        assertThat(ResetPeriod.NEVER.periodKey(date)).isEqualTo("1");
    }

    @Test
    void shouldUseIsoWeekAcrossYearBoundary() {
        assertThat(ResetPeriod.WEEKLY.periodKey(LocalDate.of(2025, 12, 29))).isEqualTo("2026-W01");
        assertThat(ResetPeriod.WEEKLY.periodKey(LocalDate.of(2026, 1, 1))).isEqualTo("2026-W01");
        assertThat(ResetPeriod.WEEKLY.periodKey(LocalDate.of(2026, 1, 4))).isEqualTo("2026-W01");
        assertThat(ResetPeriod.WEEKLY.periodKey(LocalDate.of(2026, 1, 5))).isEqualTo("2026-W02");
        assertThat(ResetPeriod.WEEKLY.periodKey(LocalDate.of(2027, 1, 3))).isEqualTo("2026-W53");
        assertThat(ResetPeriod.WEEKLY.periodKey(LocalDate.of(2027, 1, 4))).isEqualTo("2027-W01");
    }
}
