package com.argent.servicecommon.transfer.imp;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySummary;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.error.ValidationErrorsException;
import com.argent.servicecommon.security.CurrentUser;
import com.argent.servicecommon.transfer.TransferProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * REST импорта листов XLSX/CSV в справочники.
 *
 * <ul>
 *     <li>{@code POST /api/import/dictionaries/{code}} — multipart {@code file} (XLSX или CSV по
 *     расширению), {@code mode} = {@code REPLACE|UPSERT}, необязательные {@code sheet} (имя листа XLSX,
 *     по умолчанию первый), {@code keyFields} (поля через запятую), {@code mapping} (JSON
 *     {@code {"Заголовок": "поле"}}; {@code null} — колонка игнорируется). Ответ — {@link ImportReport};
 *     отменённый импорт — 400 ProblemDetail «Ошибка импорта» с отчётом в свойстве {@code report}.
 *     Требует действие {@code import} и право {@code write} на справочник.</li>
 *     <li>{@code POST /api/import/dictionaries} — все листы XLSX; лист сопоставляется со справочником
 *     по коду или названию без учёта регистра; отдельная транзакция и отчёт на каждый лист, ключи UPSERT —
 *     по умолчанию сущности. Требует действие {@code import}; лист без права {@code write} не
 *     импортируется. Ответ — список {@link SheetImportResult}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/import/dictionaries")
public class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private final TableReader reader;
    private final DictionaryImportService importService;
    private final DynamicDictionarySchemaService schemas;
    private final DynamicDictionaryCatalog catalog;
    private final TransferProperties properties;

    public ImportController(TableReader reader, DictionaryImportService importService,
                            DynamicDictionarySchemaService schemas, DynamicDictionaryCatalog catalog,
                            TransferProperties properties) {
        this.reader = Objects.requireNonNull(reader);
        this.importService = Objects.requireNonNull(importService);
        this.schemas = Objects.requireNonNull(schemas);
        this.catalog = Objects.requireNonNull(catalog);
        this.properties = Objects.requireNonNull(properties);
    }

    /** Импортирует один лист в справочник {@code code}. */
    @PostMapping(path = "/{code}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@argentAccess.action('import') and @argentAccess.table(#code, 'write')")
    public ImportReport importDictionary(@PathVariable("code") String code,
                                         @RequestParam("file") MultipartFile file,
                                         @RequestParam("mode") String mode,
                                         @RequestParam(name = "sheet", required = false) String sheet,
                                         @RequestParam(name = "keyFields", required = false) String keyFields,
                                         @RequestParam(name = "mapping", required = false) String mapping) {
        ImportMode importMode = ImportMode.parse(mode);
        SheetData data = isCsv(file) ? reader.readCsv(open(file)) : selectSheet(reader.readXlsx(open(file)), sheet);
        ColumnMapping columns = mapping == null || mapping.isBlank()
                ? ColumnMapping.auto(schemas.describe(code), data.headers())
                : new ColumnMapping(parseMapping(mapping), List.of());
        return importService.importSheet(code, data, importMode, columns.withKeyFields(splitKeys(keyFields)));
    }

    /** Импортирует все листы книги XLSX: лист → справочник по коду или названию. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@argentAccess.action('import')")
    public List<SheetImportResult> importWorkbook(@RequestParam("file") MultipartFile file,
                                                  @RequestParam("mode") String mode) {
        ImportMode importMode = ImportMode.parse(mode);
        if (isCsv(file)) {
            throw new IllegalArgumentException("Для импорта всех листов нужен файл XLSX");
        }
        List<SheetData> sheets = reader.readXlsx(open(file));
        List<DynamicDictionarySummary> dictionaries = catalog.list();
        List<SheetImportResult> results = new ArrayList<>();
        for (SheetData sheet : sheets) {
            int total = (int) sheet.rows().stream().filter(row -> !row.blank()).count();
            String code = dictionaryFor(sheet.name(), dictionaries).orElse(null);
            if (code == null) {
                results.add(new SheetImportResult(sheet.name(), null,
                        ImportReport.rejected(total, "Справочник для листа не найден: " + sheet.name())));
            } else if (!CurrentUser.canTable(code, "write")) {
                results.add(new SheetImportResult(sheet.name(), code,
                        ImportReport.rejected(total, "Нет права записи в справочник")));
            } else {
                results.add(new SheetImportResult(sheet.name(), code, importSheet(code, sheet, importMode, total)));
            }
        }
        return results;
    }

    private ImportReport importSheet(String code, SheetData sheet, ImportMode mode, int total) {
        try {
            return importService.importSheetInNewTransaction(code, sheet, mode, null);
        } catch (ImportFailedException exception) {
            return exception.report();
        } catch (IllegalArgumentException | IllegalStateException | DynamicDictionaryNotFoundException exception) {
            return ImportReport.rejected(total, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Ошибка импорта листа {} в справочник {}", sheet.name(), code, exception);
            return ImportReport.rejected(total, "Не удалось импортировать лист");
        }
    }

    /** Совпадение с кодом справочника важнее совпадения с названием. */
    private static Optional<String> dictionaryFor(String sheetName, List<DynamicDictionarySummary> dictionaries) {
        String name = sheetName.strip();
        return dictionaries.stream().filter(dictionary -> dictionary.code().equalsIgnoreCase(name))
                .map(DynamicDictionarySummary::code).findFirst()
                .or(() -> dictionaries.stream()
                        .filter(dictionary -> dictionary.name() != null && dictionary.name().strip().equalsIgnoreCase(name))
                        .map(DynamicDictionarySummary::code).findFirst());
    }

    private static SheetData selectSheet(List<SheetData> sheets, String name) {
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("Файл не содержит листов");
        }
        if (name == null || name.isBlank()) {
            return sheets.getFirst();
        }
        return sheets.stream().filter(sheet -> sheet.name().strip().equalsIgnoreCase(name.strip())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Лист не найден: " + name));
    }

    private InputStream open(MultipartFile file) {
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new IllegalArgumentException(TableReader.TOO_LARGE);
        }
        try {
            return file.getInputStream();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Не удалось прочитать файл", exception);
        }
    }

    private static boolean isCsv(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv")) return true;
        String contentType = file.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("csv");
    }

    private static List<String> splitKeys(String keyFields) {
        if (keyFields == null || keyFields.isBlank()) return List.of();
        return Arrays.stream(keyFields.split(",")).map(String::strip).filter(key -> !key.isEmpty()).toList();
    }

    private static Map<String, String> parseMapping(String mapping) {
        Map<?, ?> parsed;
        try {
            parsed = JsonMapper.shared().readValue(mapping, Map.class);
        } catch (JacksonException exception) {
            throw invalidMapping();
        }
        if (parsed == null) {
            throw invalidMapping();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : parsed.entrySet()) {
            if (entry.getValue() != null && !(entry.getValue() instanceof String)) {
                throw invalidMapping();
            }
            result.put(String.valueOf(entry.getKey()), (String) entry.getValue());
        }
        return result;
    }

    /** 400 «Ошибка валидации» с ошибкой поля {@code mapping}; ожидается JSON {@code {"Заголовок": "поле"}}. */
    private static ValidationErrorsException invalidMapping() {
        return new ValidationErrorsException(Map.of("mapping", "Некорректное сопоставление колонок"));
    }
}
