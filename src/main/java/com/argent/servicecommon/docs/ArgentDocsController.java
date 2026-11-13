package com.argent.servicecommon.docs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.argent.servicecommon.dictionary.annotation.ArgentDataTable;
import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.mapper.DictionaryProperties;
import com.argent.servicecommon.docs.openapi.ArgentApiAction;
import com.argent.servicecommon.docs.dto.ArgentDbSchema;
import com.argent.servicecommon.docs.dto.ArgentDtoSchema;
import com.argent.servicecommon.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Каталоги DTO и структуры БД. Только метаданные, без значений из пользовательских таблиц.
 * Метаданные обновляются при каждом запросе; сами JPA-модели фиксируются при запуске приложения.
 */
@RestController
@RequestMapping("/api/argent-docs")
@Tag(name = "Документация")
public class ArgentDocsController {
    private static final int MAX_TABLES = 250;
    private static final int MAX_COLUMNS = 200;
    private static final int MAX_DTO_CLASSES = 500;
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    public ArgentDocsController(JdbcTemplate jdbc, EntityManager em) {
        this.jdbc = jdbc;
        this.em = em;
    }

    @ArgentApiAction("admin")
    @GetMapping("/database")
    @Operation(summary = "Получить структуру базы данных")
    public ArgentDbSchema database() {
        requireAdmin();
        Map<String, TableInfo> registered = registeredTables();
        return jdbc.execute((ConnectionCallback<ArgentDbSchema>) connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            String schema = connection.getSchema();
            if (schema == null || schema.isBlank()) schema = "public";
            List<ArgentDbSchema.Table> tables = new ArrayList<>();
            try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next() && tables.size() < MAX_TABLES) {
                    String table = rs.getString("TABLE_NAME");
                    if (table.startsWith("flyway_")) continue;
                    TableInfo info = registered.get(table);
                    String tableDescription = emptyTo(rs.getString("REMARKS"), info == null ? "" : info.description());
                    Set<String> keys = new HashSet<>();
                    try (ResultSet pks = meta.getPrimaryKeys(null, schema, table)) {
                        while (pks.next()) keys.add(pks.getString("COLUMN_NAME"));
                    }
                    List<ArgentDbSchema.Column> columns = new ArrayList<>();
                    try (ResultSet cols = meta.getColumns(null, schema, table, "%")) {
                        while (cols.next() && columns.size() < MAX_COLUMNS) {
                            String name = cols.getString("COLUMN_NAME");
                            FieldInfo field = info == null ? null : info.columns().get(name);
                            String hint = emptyTo(cols.getString("REMARKS"), field == null ? "" : field.description());
                            String title = field == null ? name : field.title();
                            columns.add(new ArgentDbSchema.Column(name, title, hint, cols.getString("TYPE_NAME"),
                                    cols.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, keys.contains(name)));
                        }
                    }
                    List<ArgentDbSchema.Relation> relations = new ArrayList<>();
                    try (ResultSet fks = meta.getImportedKeys(null, schema, table)) {
                        while (fks.next()) {
                            relations.add(new ArgentDbSchema.Relation(fks.getString("FKCOLUMN_NAME"),
                                    fks.getString("PKTABLE_NAME"), fks.getString("PKCOLUMN_NAME")));
                        }
                    }
                    tables.add(new ArgentDbSchema.Table(table,
                            info == null ? table : info.title(), tableDescription,
                            info == null ? (table.startsWith("common_") ? "Системные таблицы" : "Другие таблицы")
                                    : info.group(), List.copyOf(columns), List.copyOf(relations)));
                }
            }
            tables.sort(Comparator.comparing(ArgentDbSchema.Table::group)
                    .thenComparing(ArgentDbSchema.Table::title));
            return new ArgentDbSchema(schema, List.copyOf(tables));
        });
    }

    @ArgentApiAction("admin")
    @GetMapping("/dtos")
    @Operation(summary = "Получить каталог DTO")
    public List<ArgentDtoSchema> dtos() {
        requireAdmin();
        Map<String, ArgentDtoSchema> result = new LinkedHashMap<>();
        Set<String> scanPackages = new LinkedHashSet<>();
        scanPackages.add("com.argent.servicecommon");
        for (var type : em.getMetamodel().getEntities()) {
            Class<?> entity = type.getJavaType();
            String pkg = entity.getPackageName();
            if (pkg.endsWith(".entity") || pkg.endsWith(".model") || pkg.endsWith(".domain")) {
                pkg = pkg.substring(0, pkg.lastIndexOf('.'));
            }
            if (!pkg.isBlank()) scanPackages.add(pkg);
            ArgentDictionary dictionary = entity.getAnnotation(ArgentDictionary.class);
            ArgentDataTable dataTable = entity.getAnnotation(ArgentDataTable.class);
            if (dictionary == null && dataTable == null) continue;
            Table table = entity.getAnnotation(Table.class);
            Class<?> dtoClass = dictionary != null ? dictionary.dtoClass() : dataTable.dtoClass();
            String group = dictionary != null ? dictionary.group() : dataTable.group();
            result.put(dtoClass.getName(), describeDto(dtoClass, table == null ? "" : table.name(), group));
        }
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(
                Pattern.compile(".*(Dto|DTO|Request|Response)$")));
        for (String basePackage : scanPackages) {
            for (var definition : scanner.findCandidateComponents(basePackage)) {
                if (result.size() >= MAX_DTO_CLASSES) break;
                String name = definition.getBeanClassName();
                if (name == null || result.containsKey(name)) continue;
                try {
                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    Class<?> clazz = Class.forName(name, false, loader == null
                            ? getClass().getClassLoader() : loader);
                    if (!clazz.isInterface() && !clazz.isEnum() && !clazz.isAnnotation()) {
                        result.put(name, describeDto(clazz, "", "Общие модели"));
                    }
                } catch (ClassNotFoundException | LinkageError exception) {
                    // Необязательные зависимости не должны ломать документацию.
                }
            }
            if (result.size() >= MAX_DTO_CLASSES) break;
        }
        return result.values().stream().sorted(Comparator.comparing(ArgentDtoSchema::title)
                .thenComparing(ArgentDtoSchema::javaClass)).toList();
    }

    private ArgentDtoSchema describeDto(Class<?> clazz, String table, String group) {
        Schema schema = clazz.getAnnotation(Schema.class);
        String title = schema == null ? clazz.getSimpleName() : emptyTo(schema.title(), clazz.getSimpleName());
        String description = schema == null ? "" : schema.description();
        List<ArgentDtoSchema.Field> fields = DictionaryProperties.dtoProperties(clazz).stream()
                .map(property -> {
                    Annotation[] annotations = propertyAnnotations(clazz, property.name());
                    Schema display = find(annotations, Schema.class);
                    DictionaryField dictionary = property.options();
                    String label = display == null ? "" : display.title();
                    if (label.isBlank() && dictionary != null) label = dictionary.label();
                    if (label.isBlank()) label = property.name();
                    String hint = display == null ? "" : display.description();
                    if (hint.isBlank() && dictionary != null) hint = dictionary.description();
                    boolean required = has(annotations, NotNull.class) || has(annotations, NotBlank.class)
                            || (display != null && display.requiredMode() == Schema.RequiredMode.REQUIRED);
                    return new ArgentDtoSchema.Field(property.name(), label, hint,
                            property.type().getSimpleName(), property.readOnly(), required,
                            display == null ? "" : display.example());
                }).toList();
        return new ArgentDtoSchema(clazz.getSimpleName(), clazz.getName(), table,
                title, description, group, fields);
    }

    private Map<String, TableInfo> registeredTables() {
        Map<String, TableInfo> result = new HashMap<>();
        for (var type : em.getMetamodel().getEntities()) {
            Class<?> entity = type.getJavaType();
            Table jpa = entity.getAnnotation(Table.class);
            if (jpa == null || jpa.name().isBlank()) continue;
            ArgentDictionary dictionary = entity.getAnnotation(ArgentDictionary.class);
            ArgentDataTable dataTable = entity.getAnnotation(ArgentDataTable.class);
            if (dictionary == null && dataTable == null) continue;
            Class<?> dto = dictionary != null ? dictionary.dtoClass() : dataTable.dtoClass();
            String title = dictionary != null ? dictionary.name() : dataTable.name();
            String group = dictionary != null ? dictionary.group() : dataTable.group();
            Schema schema = entity.getAnnotation(Schema.class);
            String hint = schema == null ? "" : schema.description();
            Map<String, FieldInfo> columns = new HashMap<>();
            for (var property : DictionaryProperties.dtoProperties(dto)) {
                Annotation[] annotations = propertyAnnotations(dto, property.name());
                Schema fieldSchema = find(annotations, Schema.class);
                String label = fieldSchema == null ? "" : fieldSchema.title();
                if (label.isBlank()) label = property.label();
                if (label.isBlank()) label = property.name();
                String descr = fieldSchema == null ? "" : fieldSchema.description();
                if (descr.isBlank()) descr = property.description();
                String columnName = sqlColumn(entity, property.name());
                columns.put(columnName, new FieldInfo(label, descr));
            }
            result.put(jpa.name(), new TableInfo(title, hint, group, columns));
        }
        return result;
    }

    private static String sqlColumn(Class<?> entity, String javaName) {
        for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(javaName);
                Column column = field.getAnnotation(Column.class);
                if (column != null && !column.name().isBlank()) return column.name();
                JoinColumn join = field.getAnnotation(JoinColumn.class);
                if (join != null && !join.name().isBlank()) return join.name();
                break;
            } catch (NoSuchFieldException ignored) { }
        }
        return javaName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static Annotation[] propertyAnnotations(Class<?> clazz, String name) {
        if (clazz.isRecord()) for (RecordComponent component : clazz.getRecordComponents()) {
            if (component.getName().equals(name)) return component.getAnnotations();
        }
        for (Class<?> type = clazz; type != null && type != Object.class; type = type.getSuperclass()) {
            try { return type.getDeclaredField(name).getAnnotations(); }
            catch (NoSuchFieldException ignored) { }
        }
        return new Annotation[0];
    }

    private static <T extends Annotation> T find(Annotation[] annotations, Class<T> type) {
        for (Annotation item : annotations) if (type.isInstance(item)) return type.cast(item);
        return null;
    }

    private static boolean has(Annotation[] annotations, Class<? extends Annotation> type) {
        return find(annotations, type) != null;
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void requireAdmin() {
        if (!CurrentUser.hasAction("admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Просмотр схем БД и DTO доступен администратору.");
        }
    }

    private record FieldInfo(String title, String description) { }
    private record TableInfo(String title, String description, String group,
                             Map<String, FieldInfo> columns) { }
}
