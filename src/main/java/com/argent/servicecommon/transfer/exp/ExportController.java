package com.argent.servicecommon.transfer.exp;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * REST экспорта таблиц в XLSX, CSV и DOCX. Ответ — файл с {@code Content-Disposition: attachment}
 * (имя в UTF-8 по заголовку таблицы) и {@code X-Content-Type-Options: nosniff}. PDF не поддерживается:
 * 400 «Формат PDF не поддерживается».
 *
 * <ul>
 *     <li>{@code POST /api/export/{format}} — тело {@link TableData}; доступен любому вошедшему
 *     (данные присылает сам клиент). Больше {@code argent.transfer.max-rows} строк — 400.</li>
 *     <li>{@code GET /api/export/dictionaries/{code}?format=xlsx|csv|docx&search=&sort=} — справочник
 *     целиком с поиском и сортировкой универсальной таблицы; требует право {@code read} на
 *     справочник. Формат по умолчанию — {@code xlsx}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final TableExporter exporter;
    private final DictionaryExportService dictionaryExport;
    private final int maxRows;

    public ExportController(TableExporter exporter, DictionaryExportService dictionaryExport, int maxRows) {
        this.exporter = Objects.requireNonNull(exporter);
        this.dictionaryExport = Objects.requireNonNull(dictionaryExport);
        this.maxRows = maxRows;
    }

    /** Выгружает присланную таблицу. */
    @PostMapping(path = "/{format}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> exportTable(@PathVariable("format") String format, @RequestBody TableData data) {
        ExportFormat exportFormat = ExportFormat.parse(format);
        if (data == null) {
            throw new IllegalArgumentException("Не передана таблица для экспорта");
        }
        if (data.rows().size() > maxRows) {
            throw new IllegalArgumentException("Слишком много строк для экспорта: больше " + maxRows);
        }
        return file(data, exportFormat);
    }

    /** Выгружает справочник {@code code}. */
    @GetMapping("/dictionaries/{code}")
    @PreAuthorize("@argentAccess.table(#code, 'read')")
    public ResponseEntity<byte[]> exportDictionary(@PathVariable("code") String code,
                                                   @RequestParam(name = "format", defaultValue = "xlsx") String format,
                                                   @RequestParam(name = "search", required = false) String search,
                                                   @RequestParam(name = "sort", required = false) String sort) {
        ExportFormat exportFormat = ExportFormat.parse(format);
        return file(dictionaryExport.build(code, search, sort), exportFormat);
    }

    private ResponseEntity<byte[]> file(TableData data, ExportFormat format) {
        byte[] body = switch (format) {
            case XLSX -> exporter.toXlsx(data);
            case CSV -> exporter.toCsv(data);
            case DOCX -> exporter.toDocx(data);
        };
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName(data.title()) + "." + format.extension(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(format.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /** Имя файла из заголовка: без символов, запрещённых в именах файлов, не длиннее 100 символов. */
    public static String fileName(String title) {
        String cleaned = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (cleaned.length() > 100) {
            int end = Character.isHighSurrogate(cleaned.charAt(99)) ? 99 : 100;
            cleaned = cleaned.substring(0, end).strip();
        }
        return cleaned.isEmpty() || cleaned.chars().allMatch(ch -> ch == '.') ? "export" : cleaned;
    }
}
