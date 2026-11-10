package com.argent.servicecommon.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Значения по умолчанию и проверка {@link ReportProperties}. */
class ReportPropertiesTest {

    private static ReportProperties of(int maxRows, Duration timeout, String font) {
        return new ReportProperties(List.of(), maxRows, timeout, Locale.forLanguageTag("ru-RU"), font, true);
    }

    @Test
    void bindsDefaults() {
        ReportProperties properties = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("argent.reports", ReportProperties.class);
        assertThat(properties.preload()).isEmpty();
        assertThat(properties.maxRows()).isEqualTo(100_000);
        assertThat(properties.queryTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.locale()).isEqualTo(Locale.forLanguageTag("ru-RU"));
        assertThat(properties.defaultFont()).isEqualTo("DejaVu Sans");
        assertThat(properties.watchFiles()).isTrue();
    }

    @Test
    void rejectsNonPositiveMaxRows() {
        assertThatThrownBy(() -> of(0, Duration.ofSeconds(1), "X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("argent.reports.max-rows");
    }

    @Test
    void rejectsMissingOrNonPositiveTimeout() {
        assertThatThrownBy(() -> of(1, null, "X")).hasMessageContaining("argent.reports.query-timeout");
        assertThatThrownBy(() -> of(1, Duration.ZERO, "X")).hasMessageContaining("argent.reports.query-timeout");
    }

    @Test
    void blankFontFallsBackToDefaultAndNullPreloadToEmpty() {
        ReportProperties properties = new ReportProperties(null, 1, Duration.ofSeconds(1), Locale.ROOT, "  ", true);
        assertThat(properties.defaultFont()).isEqualTo("DejaVu Sans");
        assertThat(properties.preload()).isEmpty();
    }

    @Test
    void emptyLocaleFallsBackToRussian() {
        ReportProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("argent.reports.locale", "")))
                .bindOrCreate("argent.reports", ReportProperties.class);
        assertThat(properties.locale()).isEqualTo(Locale.forLanguageTag("ru-RU"));
        assertThat(new ReportProperties(null, 1, Duration.ofSeconds(1), null, "X", true).locale())
                .isEqualTo(Locale.forLanguageTag("ru-RU"));
    }
}
