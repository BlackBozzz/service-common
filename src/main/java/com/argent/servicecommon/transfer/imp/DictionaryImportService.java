package com.argent.servicecommon.transfer.imp;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySchema;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicFieldDefinition;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.metadata.RegisteredDictionary;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;
import com.argent.servicecommon.dictionary.exception.DictionaryMappingException;
import com.argent.servicecommon.dictionary.mapper.DictionaryProperties;
import com.argent.servicecommon.dictionary.service.DictionaryDtoCrudService;
import com.argent.servicecommon.transfer.TransferProperties;
import com.argent.servicecommon.transfer.TransferProperties.ImportOnError;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Импорт листа таблицы в зарегистрированный справочник через публичные сервисы модуля справочников.
 *
 * <h2>Режимы</h2>
 * <ul>
 *     <li>{@link ImportMode#REPLACE}: только для справочников без коллекций ({@code @ElementCollection},
 *     {@code @OneToMany}, {@code @ManyToMany}). Лист без строк данных отменяется («Лист не содержит
 *     данных») без удаления. Сначала проверяются все строки листа; если есть ошибка
 *     листа, при {@code fail-all} — любая ошибка, при {@code skip-row} — нет ни одной корректной строки,
 *     то удаление не выполняется и лист отменяется. Иначе все строки таблицы удаляются одним
 *     {@code DELETE}, затем корректные строки создаются через {@link DictionaryDtoCrudService#create}.
 *     Нарушение внешнего ключа при удалении — {@link IllegalStateException} (REST 409).</li>
 *     <li>{@link ImportMode#UPSERT}: запись ищется по ключевым полям ({@link ColumnMapping#keyFields()}
 *     или {@link EntityKeyFields}). Простые ключевые поля — фильтры равенства
 *     {@link DynamicDictionaryQueryService#page}; ссылочные ключевые поля сравниваются по
 *     идентификатору в памяти среди найденных страниц (если простых ключей нет — просматриваются все
 *     записи справочника). Найденная запись читается как DTO, непустые ячейки листа накладываются
 *     поверх, затем {@code update}; пустая ячейка и отсутствующая колонка оставляют значение.</li>
 * </ul>
 *
 * <h2>Ошибки</h2>
 * <p>Ошибки преобразования, ссылок, ключей и Bean Validation находятся до записи в базу и собираются
 * по строкам. {@code fail-all}: после первой ошибки строки только проверяются, в конце бросается
 * {@link ImportFailedException} со всеми ошибками, транзакция откатывается. {@code skip-row}: ошибочные
 * строки пропускаются. Ошибка самой базы данных (уникальность, NOT NULL и т. п.) делает транзакцию
 * непригодной, поэтому при любой стратегии лист отменяется с {@link ImportFailedException}.</p>
 */
public class DictionaryImportService {

    public static final String REPLACE_WITH_COLLECTIONS =
            "REPLACE недоступен для справочника с коллекциями, используйте UPSERT";
    public static final String REFERENCED = "На записи справочника ссылаются другие данные";
    public static final String KEY_FIELDS_REQUIRED = "Укажите ключевые поля для режима UPSERT";
    public static final String UNMAPPED_COLUMN = "Колонка не сопоставлена с полем: ";
    public static final String EMPTY_SHEET = "Лист не содержит данных";
    public static final String REPLACE_SKIPPED =
            "REPLACE не выполнен: есть ошибки листа или нет корректных строк, записи справочника сохранены";

    private static final Logger log = LoggerFactory.getLogger(DictionaryImportService.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final int PAGE_SIZE = 100;

    private final DynamicDictionaryCatalog catalog;
    private final DynamicDictionarySchemaService schemas;
    private final DynamicDictionaryQueryService queries;
    private final DictionaryDtoCrudService crud;
    private final ValueConverter converter;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final AuditRecorder auditRecorder;
    private final TransferProperties properties;

    public DictionaryImportService(DynamicDictionaryCatalog catalog, DynamicDictionarySchemaService schemas,
                                   DynamicDictionaryQueryService queries, DictionaryDtoCrudService crud,
                                   ValueConverter converter, EntityManager entityManager, JdbcTemplate jdbc,
                                   Validator validator, AuditRecorder auditRecorder,
                                   TransferProperties properties) {
        this.catalog = Objects.requireNonNull(catalog);
        this.schemas = Objects.requireNonNull(schemas);
        this.queries = Objects.requireNonNull(queries);
        this.crud = Objects.requireNonNull(crud);
        this.converter = Objects.requireNonNull(converter);
        this.entityManager = Objects.requireNonNull(entityManager);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.validator = Objects.requireNonNull(validator);
        this.auditRecorder = Objects.requireNonNull(auditRecorder);
        this.properties = Objects.requireNonNull(properties);
    }

    /**
     * Импортирует лист со стратегией ошибок из {@code argent.transfer.import-on-error}.
     *
     * @param code    код справочника
     * @param sheet   прочитанный лист
     * @param mode    режим импорта
     * @param mapping сопоставление колонок; {@code null} — автоматическое без явных ключей
     * @return отчёт; при отмене листа — {@link ImportFailedException} с отчётом
     */
    @Transactional
    public ImportReport importSheet(String code, SheetData sheet, ImportMode mode, ColumnMapping mapping) {
        return run(code, sheet, mode, mapping, properties.importOnError());
    }

    /** Импортирует лист с указанной стратегией ошибок. */
    @Transactional
    public ImportReport importSheet(String code, SheetData sheet, ImportMode mode, ColumnMapping mapping,
                                    ImportOnError onError) {
        return run(code, sheet, mode, mapping, onError);
    }

    /** Импортирует лист в отдельной транзакции (многолистовой импорт: транзакция на лист). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportReport importSheetInNewTransaction(String code, SheetData sheet, ImportMode mode,
                                                    ColumnMapping mapping) {
        return run(code, sheet, mode, mapping, properties.importOnError());
    }

    private ImportReport run(String code, SheetData sheet, ImportMode mode, ColumnMapping mapping,
                             ImportOnError onError) {
        Objects.requireNonNull(sheet, "Лист не может быть null");
        Objects.requireNonNull(mode, "Режим импорта не может быть null");
        Objects.requireNonNull(onError, "Стратегия ошибок не может быть null");
        RegisteredDictionary dictionary = catalog.require(code);
        DynamicDictionarySchema schema = schemas.describe(code);
        Plan plan = plan(dictionary, schema, sheet,
                mapping == null ? ColumnMapping.auto(schema, sheet.headers()) : mapping, mode);

        List<RowData> rows = sheet.rows().stream().filter(row -> !row.blank()).toList();
        Progress progress = new Progress(rows.size());
        plan.unmapped().forEach(header -> progress.sheetError(UNMAPPED_COLUMN + header));
        Map<String, Map<String, Object>> referenceCache = new HashMap<>();
        if (mode == ImportMode.REPLACE) {
            replace(code, dictionary.entityClass(), plan, rows, progress, onError, referenceCache);
        } else {
            for (RowData row : rows) {
                Prepared prepared = prepare(code, plan, mode, row, progress, referenceCache);
                if (prepared != null && progress.canWrite(onError)) {
                    write(code, plan, prepared, progress);
                }
            }
        }
        if (progress.hasErrors() && onError == ImportOnError.FAIL_ALL) {
            throw new ImportFailedException(progress.cancelled());
        }
        return progress.report();
    }

    /**
     * REPLACE: сначала проверяются все строки, и только затем удаляются старые записи. Записи
     * сохраняются, если есть ошибка листа (например, несопоставленная колонка), если при {@code fail-all}
     * есть хоть одна ошибка или если при {@code skip-row} нет ни одной корректной строки — в последних
     * случаях лист отменяется с {@link ImportFailedException}.
     */
    private void replace(String code, Class<?> entityClass, Plan plan, List<RowData> rows, Progress progress,
                         ImportOnError onError, Map<String, Map<String, Object>> referenceCache) {
        if (rows.isEmpty()) {
            // Пустой лист или только заголовки: REPLACE очистил бы справочник целиком.
            progress.sheetError(EMPTY_SHEET);
            throw new ImportFailedException(progress.cancelled());
        }
        List<Prepared> valid = new ArrayList<>();
        for (RowData row : rows) {
            Prepared prepared = prepare(code, plan, ImportMode.REPLACE, row, progress, referenceCache);
            if (prepared != null) valid.add(prepared);
        }
        if (onError == ImportOnError.FAIL_ALL && progress.hasErrors()) {
            return;
        }
        if (progress.hasSheetErrors() || valid.isEmpty()) {
            progress.sheetError(REPLACE_SKIPPED);
            throw new ImportFailedException(progress.cancelled());
        }
        replaceAll(code, entityClass);
        for (Prepared prepared : valid) {
            write(code, plan, prepared, progress);
        }
    }

    /**
     * Преобразует и проверяет строку без записи в базу. Ошибки строки попадают в {@code progress};
     * результат {@code null} означает ошибочную строку.
     */
    private Prepared prepare(String code, Plan plan, ImportMode mode, RowData row, Progress progress,
                             Map<String, Map<String, Object>> referenceCache) {
        List<ImportError> errors = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Column column : plan.columns()) {
            String raw = row.value(column.header());
            if (raw == null || raw.isBlank()) continue;
            try {
                values.put(column.field().name(),
                        converter.convert(column.field(), column.javaType(), raw, referenceCache));
            } catch (IllegalArgumentException exception) {
                errors.add(new ImportError(row.excelRow(), column.field().name(), exception.getMessage()));
            }
        }
        Object existing = null;
        if (mode == ImportMode.UPSERT && errors.isEmpty()) {
            for (String key : plan.keyFields()) {
                if (!values.containsKey(key)) {
                    errors.add(new ImportError(row.excelRow(), key, "Не заполнено ключевое поле"));
                }
            }
            if (errors.isEmpty()) {
                List<Object> found = findExisting(code, plan, values);
                if (found.size() > 1) {
                    errors.add(new ImportError(row.excelRow(), null,
                            "Найдено несколько записей с такими значениями ключевых полей"));
                } else if (!found.isEmpty()) {
                    existing = found.getFirst();
                }
            }
        }
        if (!errors.isEmpty()) {
            progress.rowFailed(errors);
            return null;
        }

        Object dto;
        try {
            dto = existing == null ? newDto(plan, values) : mergedDto(plan, existing, values);
        } catch (RuntimeException exception) {
            log.debug("Не удалось сформировать DTO строки {}", row.excelRow(), exception);
            progress.rowFailed(List.of(new ImportError(row.excelRow(), null, "Не удалось сформировать запись")));
            return null;
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            progress.rowFailed(violations.stream()
                    .map(violation -> new ImportError(row.excelRow(), field(violation.getPropertyPath()),
                            violation.getMessage()))
                    .sorted(Comparator.comparingInt((ImportError error) -> plan.order(error.field()))
                            .thenComparing(ImportError::message))
                    .toList());
            return null;
        }
        return new Prepared(row.excelRow(), dto,
                existing == null ? null : String.valueOf(DictionaryProperties.read(existing, plan.idField())));
    }

    /** Записывает проверенную строку; ошибка базы данных отменяет весь лист. */
    private void write(String code, Plan plan, Prepared prepared, Progress progress) {
        try {
            if (prepared.existingId() == null) {
                createTyped(code, prepared.dto(), plan);
                progress.created++;
            } else {
                updateTyped(code, prepared.existingId(), prepared.dto(), plan);
                progress.updated++;
            }
            entityManager.flush();
        } catch (RuntimeException exception) {
            // После ошибки JPA транзакция помечена на откат: продолжать лист нельзя.
            progress.rowFailed(List.of(new ImportError(prepared.row(), null, writeFailure(exception))));
            throw new ImportFailedException(progress.cancelled());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void createTyped(String code, Object dto, Plan plan) {
        crud.create(code, dto, (Class) plan.entityClass(), (Class) plan.dtoClass());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void updateTyped(String code, String id, Object dto, Plan plan) {
        crud.update(code, id, dto, (Class) plan.entityClass(), (Class) plan.dtoClass());
    }

    /** Ищет записи с теми же ключами; результат — DTO справочника. */
    private List<Object> findExisting(String code, Plan plan, Map<String, Object> values) {
        Map<String, String> filters = new LinkedHashMap<>();
        for (String key : plan.scalarKeys()) {
            filters.put(key, filterValue(values.get(key)));
        }
        List<Object> matches = new ArrayList<>();
        for (int page = 0; ; page++) {
            DynamicPage<?> result = queries.page(code, page, PAGE_SIZE, null, null, filters);
            for (Object item : result.items()) {
                if (referenceKeysMatch(plan, item, values)) {
                    matches.add(item);
                }
            }
            if (result.last() || result.items().isEmpty() || matches.size() > 1) {
                return matches;
            }
        }
    }

    private static boolean referenceKeysMatch(Plan plan, Object item, Map<String, Object> values) {
        for (DynamicFieldDefinition key : plan.referenceKeys()) {
            Object nested = DictionaryProperties.read(item, key.name());
            if (nested == null) return false;
            String idField = key.reference().idField();
            Object actual = DictionaryProperties.read(nested, idField);
            Object expected = ((Map<?, ?>) values.get(key.name())).get(idField);
            if (actual == null || !String.valueOf(actual).equals(String.valueOf(expected))) return false;
        }
        return true;
    }

    private static String filterValue(Object value) {
        if (value instanceof Enum<?> constant) return constant.name();
        if (value instanceof java.math.BigDecimal decimal) return decimal.toPlainString();
        return String.valueOf(value);
    }

    private Object newDto(Plan plan, Map<String, Object> values) {
        Map<String, Object> data = new HashMap<>();
        for (Column column : plan.columns()) {
            String name = column.field().name();
            if (values.containsKey(name) && plan.writable(name, false)) {
                data.put(name, dtoValue(column, values.get(name)));
            }
        }
        return DictionaryProperties.newDto(plan.dtoClass(), data);
    }

    private Object mergedDto(Plan plan, Object existing, Map<String, Object> values) {
        Map<String, Object> data = new HashMap<>();
        for (DictionaryProperties.Property property : DictionaryProperties.dtoProperties(plan.dtoClass())) {
            data.put(property.name(), DictionaryProperties.read(existing, property.name()));
        }
        for (Column column : plan.columns()) {
            String name = column.field().name();
            if (values.containsKey(name) && plan.writable(name, true)) {
                data.put(name, dtoValue(column, values.get(name)));
            }
        }
        return DictionaryProperties.newDto(plan.dtoClass(), data);
    }

    /** Ссылка превращается во вложенный DTO целевого справочника с идентификатором и подписью. */
    private Object dtoValue(Column column, Object value) {
        if (column.field().reference() == null || !(value instanceof Map<?, ?> reference)) {
            return value;
        }
        Class<?> nestedType = column.javaType();
        Map<String, Object> nested = new HashMap<>();
        for (DictionaryProperties.Property property : DictionaryProperties.dtoProperties(nestedType)) {
            Object raw = reference.get(property.name());
            if (raw != null) {
                nested.put(property.name(), converter.convertScalar(property.type(), String.valueOf(raw)));
            }
        }
        return DictionaryProperties.newDto(nestedType, nested);
    }

    /** Удаляет все строки таблицы справочника одним запросом в текущей транзакции. */
    private void replaceAll(String code, Class<?> entityClass) {
        entityManager.flush();
        String table = qualifiedTable(code, entityClass);
        int deleted;
        try {
            deleted = jdbc.update("DELETE FROM " + table);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException(REFERENCED, exception);
        }
        entityManager.clear();
        auditRecorder.recordReplace(code, deleted);
    }

    private String qualifiedTable(String code, Class<?> entityClass) {
        Table annotation = entityClass.getAnnotation(Table.class);
        String table = annotation != null && !annotation.name().isBlank() ? annotation.name() : code;
        String schema = annotation != null && !annotation.schema().isBlank() ? annotation.schema()
                : jdbc.execute((ConnectionCallback<String>) Connection::getSchema);
        for (String identifier : new String[]{code, table, schema}) {
            if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
                throw new IllegalStateException("Недопустимое имя таблицы справочника: " + identifier);
            }
        }
        return "\"" + schema + "\".\"" + table + "\"";
    }

    private Plan plan(RegisteredDictionary dictionary, DynamicDictionarySchema schema, SheetData sheet,
                      ColumnMapping mapping, ImportMode mode) {
        Class<?> entityClass = dictionary.entityClass();
        if (mode == ImportMode.REPLACE
                && !entityManager.getMetamodel().entity(entityClass).getPluralAttributes().isEmpty()) {
            throw new IllegalArgumentException(REPLACE_WITH_COLLECTIONS);
        }
        Map<String, DynamicFieldDefinition> fields = new LinkedHashMap<>();
        schema.fields().forEach(field -> fields.put(field.name(), field));
        Map<String, DictionaryProperties.Property> dtoProperties = new HashMap<>();
        DictionaryProperties.dtoProperties(dictionary.dtoClass())
                .forEach(property -> dtoProperties.put(property.name(), property));

        List<Column> columns = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (String header : sheet.headers()) {
            if (!mapping.headerToField().containsKey(header)) {
                unmapped.add(header);
                continue;
            }
            String fieldName = mapping.headerToField().get(header);
            if (fieldName == null || fieldName.isBlank()) continue;
            DynamicFieldDefinition field = fields.get(fieldName);
            DictionaryProperties.Property property = dtoProperties.get(fieldName);
            if (field == null || property == null) {
                throw new IllegalArgumentException("Поле не найдено в справочнике: " + fieldName);
            }
            if (columns.stream().anyMatch(column -> column.field().name().equals(fieldName))) {
                throw new IllegalArgumentException("Поле сопоставлено с несколькими колонками: " + fieldName);
            }
            columns.add(new Column(header, field, property.type()));
        }

        List<String> keyFields = List.of();
        List<String> scalarKeys = new ArrayList<>();
        List<DynamicFieldDefinition> referenceKeys = new ArrayList<>();
        if (mode == ImportMode.UPSERT) {
            boolean explicit = !mapping.keyFields().isEmpty();
            keyFields = explicit ? mapping.keyFields() : EntityKeyFields.derive(entityClass);
            if (keyFields.isEmpty()) {
                throw new IllegalArgumentException(KEY_FIELDS_REQUIRED);
            }
            for (String key : keyFields) {
                DynamicFieldDefinition field = fields.get(key);
                if (field == null) {
                    throw new IllegalArgumentException(explicit
                            ? "Поле не найдено в справочнике: " + key : KEY_FIELDS_REQUIRED);
                }
                if (columns.stream().noneMatch(column -> column.field().name().equals(key))) {
                    throw new IllegalArgumentException("Ключевое поле не сопоставлено с колонкой: " + key);
                }
                if (field.reference() != null && !field.reference().multiple()) {
                    referenceKeys.add(field);
                } else if (field.reference() == null && field.visible()
                        && !"array".equals(field.type()) && !"object".equals(field.type())) {
                    scalarKeys.add(key);
                } else {
                    throw new IllegalArgumentException("Поле не может быть ключевым: " + key);
                }
            }
        }
        String idField = schema.idField();
        boolean generatedId = DictionaryProperties.hasGeneratedId(entityClass, idField);
        Map<String, Integer> order = new HashMap<>();
        int index = 0;
        for (String name : fields.keySet()) {
            order.put(name, index++);
        }
        Map<String, Boolean> readOnly = new HashMap<>();
        fields.values().forEach(field -> readOnly.put(field.name(), field.readOnly()
                || dtoProperties.containsKey(field.name()) && dtoProperties.get(field.name()).readOnly()));
        return new Plan(columns, unmapped, List.copyOf(keyFields), scalarKeys, referenceKeys, entityClass,
                dictionary.dtoClass(), idField, generatedId, order, readOnly);
    }

    private static String field(Path path) {
        for (Path.Node node : path) {
            if (node.getName() != null) return node.getName();
        }
        return null;
    }

    private static String writeFailure(RuntimeException exception) {
        String suffix = "; импорт листа отменён";
        if (exception instanceof DataIntegrityViolationException
                || exception instanceof PersistenceException
                || exception instanceof DataAccessException
                || exception instanceof org.hibernate.exception.ConstraintViolationException) {
            return "Запись нарушает ограничение целостности базы данных" + suffix;
        }
        if (exception instanceof DictionaryMappingException || exception instanceof IllegalArgumentException) {
            return exception.getMessage() + suffix;
        }
        log.warn("Ошибка записи строки импорта", exception);
        return "Не удалось сохранить запись" + suffix;
    }

    /** Проверенная строка: DTO и идентификатор существующей записи ({@code null} — создать). */
    private record Prepared(int row, Object dto, String existingId) {
    }

    /** Колонка листа, сопоставленная с полем DTO. */
    private record Column(String header, DynamicFieldDefinition field, Class<?> javaType) {
    }

    /** Проверенный план импорта листа. */
    private record Plan(List<Column> columns, List<String> unmapped, List<String> keyFields,
                        List<String> scalarKeys, List<DynamicFieldDefinition> referenceKeys,
                        Class<?> entityClass, Class<?> dtoClass, String idField, boolean generatedId,
                        Map<String, Integer> fieldOrder, Map<String, Boolean> readOnly) {

        /** Поле записывается из листа: не только для чтения; ID — только при создании и без генерации. */
        boolean writable(String name, boolean updating) {
            if (Boolean.TRUE.equals(readOnly.get(name))) return false;
            if (name.equals(idField)) return !updating && !generatedId;
            return true;
        }

        int order(String name) {
            return name == null ? -1 : fieldOrder.getOrDefault(name, Integer.MAX_VALUE);
        }
    }

    /** Счётчики и ошибки листа. */
    private static final class Progress {
        private final int total;
        private final List<ImportError> errors = new ArrayList<>();
        private int created;
        private int updated;
        private int failed;

        Progress(int total) {
            this.total = total;
        }

        void sheetError(String message) {
            errors.add(new ImportError(0, null, message));
        }

        void rowFailed(List<ImportError> rowErrors) {
            errors.addAll(rowErrors);
            failed++;
        }

        boolean hasErrors() {
            return !errors.isEmpty();
        }

        boolean hasSheetErrors() {
            return errors.stream().anyMatch(error -> error.row() == 0);
        }

        boolean canWrite(ImportOnError onError) {
            return onError == ImportOnError.SKIP_ROW || errors.isEmpty();
        }

        ImportReport report() {
            return new ImportReport(total, created, updated, failed, errors);
        }

        /** Отчёт об отменённом листе: созданные и обновлённые записи откатаны. */
        ImportReport cancelled() {
            return new ImportReport(total, 0, 0, failed, errors);
        }
    }
}
