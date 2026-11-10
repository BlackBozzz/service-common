package com.argent.servicecommon.report;

import org.springframework.core.io.Resource;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Неизменяемое описание запроса отчёта: шаблон, данные, параметры, имя файла, локаль и формат. */
public final class ReportRequest {

    private final String location;
    private final Resource resource;
    private final Collection<?> data;
    private final Map<String, Object> params;
    private final String fileName;
    private final Locale locale;
    private final ReportFormat format;

    private ReportRequest(String location, Resource resource, Collection<?> data, Map<String, Object> params,
                          String fileName, Locale locale, ReportFormat format) {
        this.location = location;
        this.resource = resource;
        this.data = data;
        this.params = Collections.unmodifiableMap(params);
        this.fileName = fileName;
        this.locale = locale;
        this.format = format;
    }

    /** Шаблон по пути (например {@code classpath:reports/order.jrxml}). */
    public static ReportRequest template(String location) {
        if (location == null || location.isBlank()) {
            throw new ReportParameterException("Шаблон отчёта не задан");
        }
        return new ReportRequest(location, null, null, Map.of(), null, null, null);
    }

    /** Шаблон из ресурса. */
    public static ReportRequest template(Resource resource) {
        if (resource == null) {
            throw new ReportParameterException("Шаблон отчёта не задан");
        }
        return new ReportRequest(null, resource, null, Map.of(), null, null, null);
    }

    public ReportRequest data(Collection<?> rows) {
        return new ReportRequest(location, resource, rows, params, fileName, locale, format);
    }

    public ReportRequest param(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw new ReportParameterException("Имя параметра отчёта не задано");
        }
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.put(name, value);
        return new ReportRequest(location, resource, data, copy, fileName, locale, format);
    }

    public ReportRequest params(Map<String, ?> values) {
        ReportRequest result = this;
        if (values != null) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                result = result.param(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public ReportRequest fileName(String nameWithoutExtension) {
        return new ReportRequest(location, resource, data, params, nameWithoutExtension, locale, format);
    }

    public ReportRequest locale(Locale locale) {
        return new ReportRequest(location, resource, data, params, fileName, locale, format);
    }

    public ReportRequest format(ReportFormat format) {
        return new ReportRequest(location, resource, data, params, fileName, locale, format);
    }

    /** Путь к шаблону; {@code null}, если задан ресурс. */
    public String location() {
        return location;
    }

    /** Ресурс шаблона; {@code null}, если задан путь. */
    public Resource resource() {
        return resource;
    }

    /** Строки данных; {@code null}, если не заданы. */
    public Collection<?> data() {
        return data;
    }

    /** Неизменяемая копия параметров в порядке добавления. */
    public Map<String, Object> params() {
        return params;
    }

    public String fileName() {
        return fileName;
    }

    public Locale locale() {
        return locale;
    }

    public ReportFormat format() {
        return format;
    }
}
