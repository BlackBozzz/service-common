package com.argent.servicecommon.report;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Настройки отчётов {@code argent.reports.*}: предкомпиляция, лимиты, локаль и шрифт по умолчанию.
 * Неверные значения прерывают запуск приложения.
 *
 * @param preload      маски шаблонов, компилируемых при старте ({@code classpath*:reports/*.jrxml})
 * @param maxRows      наибольшее число строк отчёта; лишние отбрасываются
 * @param queryTimeout таймаут SQL-запроса шаблона
 * @param locale       локаль отчёта по умолчанию
 * @param defaultFont  шрифт по умолчанию (должен содержать кириллицу)
 * @param watchFiles   перекомпилировать шаблоны из файловой системы при изменении файла
 */
@ConfigurationProperties("argent.reports")
public record ReportProperties(
        @DefaultValue({}) List<String> preload,
        @DefaultValue("100000") int maxRows,
        @DefaultValue("60s") Duration queryTimeout,
        @DefaultValue("ru-RU") Locale locale,
        @DefaultValue("DejaVu Sans") String defaultFont,
        @DefaultValue("true") boolean watchFiles
) {

    private static final String DEFAULT_FONT = "DejaVu Sans";
    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("ru-RU");

    public ReportProperties {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("argent.reports.max-rows должен быть больше нуля");
        }
        if (queryTimeout == null || queryTimeout.isNegative() || queryTimeout.isZero()) {
            throw new IllegalArgumentException("argent.reports.query-timeout должен быть больше нуля");
        }
        if (defaultFont == null || defaultFont.isBlank()) {
            defaultFont = DEFAULT_FONT;
        }
        if (locale == null) {
            locale = DEFAULT_LOCALE;
        }
        preload = preload == null ? List.of() : List.copyOf(preload);
    }
}
