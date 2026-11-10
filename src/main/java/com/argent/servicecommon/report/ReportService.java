package com.argent.servicecommon.report;

import com.argent.servicecommon.report.ReportTemplateCache.CompiledTemplate;
import com.argent.servicecommon.util.FileNames;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JRRuntimeException;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReportsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.lang.reflect.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Формирует отчёт по шаблону JRXML из DTO, параметров или SQL шаблона и выгружает в PDF/XLSX/DOCX. */
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final int MAX_SHEET_NAME = 31;
    /** SQLState PostgreSQL «запрос отменён»: так приходит срабатывание {@code setQueryTimeout}. */
    private static final String QUERY_CANCELED = "57014";

    private final ReportTemplateCache templates;
    private final JasperReportsContext context;
    private final ObjectProvider<DataSource> dataSource;
    private final ReportProperties properties;

    public ReportService(ReportTemplateCache templates, JasperReportsContext context,
                         ObjectProvider<DataSource> dataSource, ReportProperties properties) {
        this.templates = templates;
        this.context = context;
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /** Формирует отчёт по запросу и возвращает готовый файл. */
    public ReportFile render(ReportRequest request) {
        ReportFormat format = request.format();
        if (format == null) {
            throw new ReportParameterException("Не указан формат отчёта");
        }
        CompiledTemplate template = request.location() != null
                ? templates.get(request.location())
                : templates.get(request.resource());
        Map<String, Object> params = buildParams(template, request);
        if (template.hasQuery() && request.data() != null) {
            throw new ReportParameterException(
                    "Шаблон " + template.name() + " сам выполняет SQL: data(...) не нужен");
        }
        if (request.data() != null && request.data().size() > properties.maxRows()) {
            log.warn("Отчёт {}: строк больше лимита argent.reports.max-rows={}, лишние отброшены",
                    template.name(), properties.maxRows());
        }
        long started = System.nanoTime();
        JasperPrint print = template.hasQuery()
                ? fillWithQuery(template, params)
                : fill(template, params, request.data() != null
                        ? new ArgentBeanDataSource(request.data())
                        : new JREmptyDataSource(1));
        String baseName = request.fileName() != null ? request.fileName() : template.name();
        byte[] content = ReportExporters.export(context, print, format, baseName, sheetName(baseName));
        log.debug("Отчёт {} сформирован в {}: страниц {}, {} мс", template.name(), format,
                print.getPages().size(), (System.nanoTime() - started) / 1_000_000);
        return ReportFile.of(content, format, baseName);
    }

    /** Компилирует шаблоны из {@code argent.reports.preload}. */
    public void preload() {
        templates.preload(properties.preload());
    }

    private Map<String, Object> buildParams(CompiledTemplate template, ReportRequest request) {
        Map<String, Object> params = new HashMap<>();
        for (Map.Entry<String, Object> entry : request.params().entrySet()) {
            String name = entry.getKey();
            JRParameter declared = template.parameters().get(name);
            if (declared == null) {
                throw new ReportParameterException(
                        "Шаблон " + template.name() + " не объявляет параметр \"" + name + "\"");
            }
            params.put(name, toParameterValue(name, entry.getValue(), declared.getValueClass()));
        }
        params.put(JRParameter.REPORT_LOCALE, request.locale() != null ? request.locale() : properties.locale());
        params.put(JRParameter.REPORT_TIME_ZONE, TimeZone.getDefault());
        params.put(JRParameter.REPORT_MAX_COUNT, properties.maxRows());
        return params;
    }

    private static Object toParameterValue(String name, Object value, Class<?> declared) {
        if (JRDataSource.class.isAssignableFrom(declared)) {
            if (value instanceof Collection<?> rows) {
                return new ArgentBeanDataSource(rows);
            }
            if (value != null && value.getClass().isArray()) {
                return new ArgentBeanDataSource(arrayToList(value));
            }
        }
        try {
            return ReportValues.convert(value, declared);
        } catch (IllegalArgumentException e) {
            throw new ReportParameterException("Параметр \"" + name + "\": " + e.getMessage());
        }
    }

    private static List<Object> arrayToList(Object array) {
        int length = Array.getLength(array);
        List<Object> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            list.add(Array.get(array, i));
        }
        return list;
    }

    /** SQL шаблона выполняется на соединении текущей транзакции (или новом из пула) с таймаутом запроса. */
    private JasperPrint fillWithQuery(CompiledTemplate template, Map<String, Object> params) {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            throw new ReportRenderException(
                    "Шаблон " + template.name() + " выполняет SQL, но в приложении нет DataSource");
        }
        Connection connection;
        try {
            connection = DataSourceUtils.getConnection(ds);
        } catch (CannotGetJdbcConnectionException e) {
            throw new ReportRenderException("Не удалось получить соединение с БД для отчёта "
                    + template.name() + ": " + e.getMessage(), e);
        }
        try {
            Connection limited = QueryTimeoutConnection.wrap(connection, properties.queryTimeout(), properties.maxRows());
            return JasperFillManager.getInstance(context).fill(template.report(), params, limited);
        } catch (JRException | JRRuntimeException e) {
            SQLException sql = findSqlException(e);
            if (sql == null) {
                throw new ReportRenderException(
                        "Не удалось сформировать отчёт " + template.name() + ": " + e.getMessage(), e);
            }
            throw new ReportRenderException("Ошибка SQL в отчёте " + template.name()
                    + " [SQLState " + sql.getSQLState() + "]: " + sqlMessage(sql), e);
        } finally {
            DataSourceUtils.releaseConnection(connection, ds);
        }
    }

    private String sqlMessage(SQLException e) {
        if (QUERY_CANCELED.equals(e.getSQLState())) {
            return "превышен таймаут " + QueryTimeoutConnection.timeoutSeconds(properties.queryTimeout()) + " с";
        }
        return e.getMessage();
    }

    private static SQLException findSqlException(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    private JasperPrint fill(CompiledTemplate template, Map<String, Object> params, JRDataSource source) {
        try {
            return JasperFillManager.getInstance(context).fill(template.report(), params, source);
        } catch (JRException | JRRuntimeException e) {
            throw new ReportRenderException(
                    "Не удалось сформировать отчёт " + template.name() + ": " + e.getMessage(), e);
        }
    }

    /** Имя листа Excel: без {@code []:*?/\}, не длиннее 31 символа. */
    static String sheetName(String baseName) {
        String name = FileNames.sanitize(baseName, "report").replaceAll("[\\[\\]:*?/\\\\]", "").strip();
        if (name.length() > MAX_SHEET_NAME) {
            int end = Character.isHighSurrogate(name.charAt(MAX_SHEET_NAME - 1)) ? MAX_SHEET_NAME - 1 : MAX_SHEET_NAME;
            name = name.substring(0, end).strip();
        }
        return name.isEmpty() ? "report" : name;
    }
}
