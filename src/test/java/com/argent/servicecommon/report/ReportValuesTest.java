package com.argent.servicecommon.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportValuesTest {

    enum Status { NEW, DONE }

    @Test
    void convertsStringToDateTypes() {
        assertThat(ReportValues.convert("2026-11-09", LocalDate.class)).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(ReportValues.convert("09.11.2026", LocalDate.class)).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(ReportValues.convert("2026-11-09T10:15:30", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 15, 30));
        assertThat(ReportValues.convert("09.11.2026 10:15", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 15));
        assertThat(ReportValues.convert("09.11.2026 10:15:30", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 15, 30));
        assertThat(ReportValues.convert("2026-11-09T10:00:00Z", Instant.class))
                .isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
        assertThat(ReportValues.convert("09.11.2026", java.util.Date.class)).isInstanceOf(java.util.Date.class);
        assertThat(ReportValues.convert("09.11.2026 10:15", java.util.Date.class)).isInstanceOf(java.util.Date.class);
    }

    @Test
    void convertsBetweenJavaTimeAndSqlDates() {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        assertThat(ReportValues.convert(now, Timestamp.class)).isEqualTo(Timestamp.from(now));
        assertThat(ReportValues.convert(Timestamp.from(now), Instant.class)).isEqualTo(now);
        assertThat(ReportValues.convert(now, java.util.Date.class)).isEqualTo(java.util.Date.from(now));
        assertThat(ReportValues.convert(now, LocalDateTime.class))
                .isEqualTo(LocalDateTime.ofInstant(now, ZoneId.systemDefault()));
        assertThat(ReportValues.convert(now, LocalDate.class))
                .isEqualTo(now.atZone(ZoneId.systemDefault()).toLocalDate());

        LocalDate day = LocalDate.of(2026, 11, 9);
        Object sqlDate = ReportValues.convert(day, java.sql.Date.class);
        assertThat(sqlDate).isInstanceOf(java.sql.Date.class);
        assertThat(((java.sql.Date) sqlDate).toLocalDate()).isEqualTo(day);
        Object utilDate = ReportValues.convert(day, java.util.Date.class);
        assertThat(((java.util.Date) utilDate).toInstant().atZone(ZoneId.systemDefault()).toLocalDate())
                .isEqualTo(day);

        LocalDateTime dt = LocalDateTime.of(2026, 11, 9, 10, 15);
        assertThat(ReportValues.convert(dt, Timestamp.class)).isEqualTo(Timestamp.valueOf(dt));
        assertThat(ReportValues.convert(new java.util.Date(Timestamp.valueOf(dt).getTime()), LocalDateTime.class))
                .isEqualTo(dt);
        assertThat(ReportValues.convert(java.sql.Date.valueOf(day), LocalDate.class)).isEqualTo(day);
        assertThat(ReportValues.convert(new java.util.Date(Timestamp.valueOf(dt).getTime()), LocalDate.class))
                .isEqualTo(day);
    }

    @Test
    void convertsNumbers() {
        assertThat((BigDecimal) ReportValues.convert("12.50", BigDecimal.class)).isEqualByComparingTo("12.50");
        assertThat(ReportValues.convert(" 42 ", Integer.class)).isEqualTo(42);
        assertThat(ReportValues.convert("5", Short.class)).isEqualTo((short) 5);
        assertThat(ReportValues.convert("1.5", Double.class)).isEqualTo(1.5d);
        assertThat(ReportValues.convert("1.5", Float.class)).isEqualTo(1.5f);
        assertThat(ReportValues.convert("99", BigInteger.class)).isEqualTo(BigInteger.valueOf(99));
        assertThat(ReportValues.convert(7, Long.class)).isEqualTo(7L);
        assertThat(ReportValues.convert(7L, Integer.class)).isEqualTo(7);
        assertThat((BigDecimal) ReportValues.convert(2.5d, BigDecimal.class)).isEqualByComparingTo("2.5");
        assertThat(ReportValues.convert(new BigDecimal("3.00"), Double.class)).isEqualTo(3.0d);
        assertThat(ReportValues.convert(3, long.class)).isEqualTo(3L);
    }

    @Test
    void rejectsFloatingOverflow() {
        assertThatThrownBy(() -> ReportValues.convert("1e400", Double.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("не приводится к Double");
        assertThatThrownBy(() -> ReportValues.convert("1e40", Float.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("не приводится к Float");
    }

    @Test
    void convertsEnumAndBooleanAndUuid() {
        assertThat(ReportValues.convert("да", Boolean.class)).isEqualTo(true);
        assertThat(ReportValues.convert("Нет", Boolean.class)).isEqualTo(false);
        assertThat(ReportValues.convert("TRUE", Boolean.class)).isEqualTo(true);
        assertThat(ReportValues.convert("1", Boolean.class)).isEqualTo(true);
        assertThat(ReportValues.convert("0", Boolean.class)).isEqualTo(false);
        assertThat(ReportValues.convert("new", Status.class)).isEqualTo(Status.NEW);
        UUID id = UUID.randomUUID();
        assertThat(ReportValues.convert(id.toString(), UUID.class)).isEqualTo(id);
    }

    @Test
    void convertsAnythingToString() {
        assertThat(ReportValues.convert(5, String.class)).isEqualTo("5");
        assertThat(ReportValues.convert(LocalDate.of(2026, 11, 9), String.class)).isEqualTo("2026-11-09");
    }

    @Test
    void keepsNullAndAssignable() {
        assertThat(ReportValues.convert(null, Integer.class)).isNull();
        List<String> list = new ArrayList<>();
        assertThat(ReportValues.convert(list, java.util.Collection.class)).isSameAs(list);
    }

    @Test
    void rejectsIncompatible() {
        assertThatThrownBy(() -> ReportValues.convert("abc", Integer.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Integer");
        assertThatThrownBy(() -> ReportValues.convert("maybe", Boolean.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportValues.convert("zzz", Status.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportValues.convert("not-a-date", LocalDate.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportValues.convert(new Object(), LocalDate.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LocalDate");
    }
}
