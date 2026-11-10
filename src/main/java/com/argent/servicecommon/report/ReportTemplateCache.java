package com.argent.servicecommon.report;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JRQuery;
import net.sf.jasperreports.engine.JRRuntimeException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.type.WhenNoDataTypeEnum;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Компилирует шаблоны JRXML и хранит результат; при слежении перекомпилирует изменённые файлы. */
public class ReportTemplateCache {

    private static final Logger log = LoggerFactory.getLogger(ReportTemplateCache.class);

    private static final String PATTERN_PREFIX = "classpath*:";
    private static final List<String> KNOWN_PREFIXES = List.of("classpath:", "file:");

    private final ResourceLoader loader;
    private final JasperReportsContext context;
    private final boolean watchFiles;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ReportTemplateCache(ResourceLoader loader, JasperReportsContext context, boolean watchFiles) {
        this.loader = loader;
        this.context = context;
        this.watchFiles = watchFiles;
    }

    /** Возвращает скомпилированный шаблон по местоположению; строка без префикса считается путём к файлу. */
    public CompiledTemplate get(String location) {
        if (location == null || location.isBlank()) {
            throw new ReportParameterException("Не указано местоположение шаблона отчёта");
        }
        String trimmed = location.trim();
        if (trimmed.startsWith(PATTERN_PREFIX)) {
            throw new ReportParameterException("Маска classpath*: допустима только в argent.reports.preload");
        }
        String normalized = KNOWN_PREFIXES.stream().anyMatch(trimmed::startsWith) ? trimmed : "file:" + trimmed;
        return get(loader.getResource(normalized), normalized);
    }

    /** Лежит ли шаблон в кэше (без компиляции); нужен тестам предзагрузки. */
    boolean isCached(String location) {
        String trimmed = location.trim();
        String normalized = KNOWN_PREFIXES.stream().anyMatch(trimmed::startsWith) ? trimmed : "file:" + trimmed;
        return entries.containsKey(keyOf(loader.getResource(normalized)));
    }

    /** Возвращает скомпилированный шаблон для готового ресурса. */
    public CompiledTemplate get(Resource resource) {
        return get(resource, resource.getDescription());
    }

    /** Компилирует все шаблоны по маскам и возвращает их число; маска без совпадений только пишется в лог. */
    public int preload(List<String> patterns) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        int compiled = 0;
        for (String pattern : patterns) {
            Resource[] resources;
            try {
                resources = resolver.getResources(pattern);
            } catch (IOException e) {
                throw new ReportTemplateException("Не удалось найти шаблоны отчётов по маске " + pattern, e);
            }
            if (resources.length == 0) {
                log.warn("По маске {} не найдено ни одного шаблона отчёта", pattern);
                continue;
            }
            for (Resource resource : resources) {
                get(resource);
                compiled++;
            }
        }
        return compiled;
    }

    private CompiledTemplate get(Resource resource, String location) {
        String key = keyOf(resource);
        Entry cached = entries.get(key);
        if (cached != null && isFresh(cached, resource)) {
            return cached.template();
        }
        // Повторная проверка внутри compute: параллельные запросы не компилируют один шаблон дважды.
        return entries.compute(key, (k, existing) ->
                existing != null && isFresh(existing, resource) ? existing : compile(resource, location)).template();
    }

    private boolean isFresh(Entry entry, Resource resource) {
        return !watches(resource) || entry.lastModified() == lastModified(resource);
    }

    private boolean watches(Resource resource) {
        return watchFiles && resource.isFile();
    }

    private Entry compile(Resource resource, String location) {
        if (!resource.exists()) {
            throw new ReportTemplateException("Шаблон отчёта не найден: " + location);
        }
        long lastModified = watches(resource) ? lastModified(resource) : -1L;
        try (InputStream in = resource.getInputStream()) {
            JasperDesign design = JRXmlLoader.load(context, in);
            WhenNoDataTypeEnum whenNoData = design.getWhenNoDataType();
            if (whenNoData == null || whenNoData == WhenNoDataTypeEnum.NO_PAGES) {
                design.setWhenNoDataType(WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL);
            }
            JasperReport report = JasperCompileManager.getInstance(context).compile(design);
            log.debug("Скомпилирован шаблон отчёта {}", location);
            return new Entry(toTemplate(report, resource, location), lastModified);
        } catch (JRException | JRRuntimeException | IOException e) {
            throw new ReportTemplateException(
                    "Не удалось скомпилировать шаблон " + location + ": " + firstMessage(e), e);
        }
    }

    private static CompiledTemplate toTemplate(JasperReport report, Resource resource, String location) {
        JRQuery query = report.getQuery();
        boolean hasQuery = query != null && query.getText() != null && !query.getText().isBlank();
        Map<String, JRParameter> parameters = new LinkedHashMap<>();
        for (JRParameter parameter : report.getParameters()) {
            if (!parameter.isSystemDefined()) {
                parameters.put(parameter.getName(), parameter);
            }
        }
        return new CompiledTemplate(report, baseName(resource, location), hasQuery,
                Collections.unmodifiableMap(parameters));
    }

    private static String baseName(Resource resource, String location) {
        String fileName = resource.getFilename() != null ? resource.getFilename() : location;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String keyOf(Resource resource) {
        try {
            return resource.getURI().toString();
        } catch (IOException | RuntimeException e) {
            return resource.getDescription();
        }
    }

    private static long lastModified(Resource resource) {
        try {
            return resource.lastModified();
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String firstMessage(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message.strip();
            }
        }
        return error.getClass().getSimpleName();
    }

    /** Скомпилированный шаблон: отчёт, имя файла без расширения, наличие SQL-запроса и пользовательские параметры. */
    public record CompiledTemplate(JasperReport report, String name, boolean hasQuery,
                                   Map<String, JRParameter> parameters) {
    }

    private record Entry(CompiledTemplate template, long lastModified) {
    }
}
