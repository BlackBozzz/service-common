package com.argent.servicecommon.transfer.exp;

import com.argent.servicecommon.transfer.exp.TableData.Column;
import com.argent.servicecommon.transfer.exp.TableData.ColumnType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Выгрузка {@link TableData} в XLSX, CSV и DOCX.
 *
 * <ul>
 *     <li>XLSX: жирная закреплённая шапка, ширина колонок по содержимому (не больше
 *     {@value #MAX_COLUMN_CHARS} символов), нативные ячейки: {@code NUMBER} — число, {@code DATE} —
 *     дата {@code dd.mm.yyyy}, {@code DATETIME} — {@code dd.mm.yyyy hh:mm}, {@code BOOLEAN} — «Да»/«Нет».
 *     Значение, которое не удалось привести к типу колонки, и даты раньше 1900 года (Excel их не
 *     поддерживает) пишутся текстом.</li>
 *     <li>CSV: UTF-8 с BOM, разделитель {@code ;}, строки через CRLF, числа с десятичной запятой —
 *     так файл открывается в Excel с русской локалью. Защита от CSV-инъекций: текст, начинающийся с
 *     {@code = + - @}, табуляции или CR, получает префикс {@code '}; настоящие числа не меняются.</li>
 *     <li>DOCX: абзац-заголовок и таблица с жирной шапкой; значения форматируются как в CSV.</li>
 * </ul>
 *
 * <p>Даты с часовым поясом ({@code Instant}, {@code OffsetDateTime}, {@code ZonedDateTime})
 * переводятся в часовой пояс сервера.</p>
 *
 * <p>Размер таблицы ограничен: не больше {@code maxColumns} колонок (по умолчанию 1000) и
 * {@code maxCells} ячеек данных, строки × колонки (по умолчанию 2 000 000); больше —
 * {@link IllegalArgumentException} (400).</p>
 */
public class TableExporter {

    /** Наибольшая ширина колонки XLSX в символах. */
    public static final int MAX_COLUMN_CHARS = 60;

    /** Наибольшее число колонок по умолчанию. */
    public static final int DEFAULT_MAX_COLUMNS = 1000;

    /** Наибольшее число ячеек данных (строки × колонки) по умолчанию. */
    public static final long DEFAULT_MAX_CELLS = 2_000_000L;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1900, 1, 1);
    private static final int MAX_CELL_TEXT = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private final int maxColumns;
    private final long maxCells;

    /** Экспортёр с лимитами по умолчанию: 1000 колонок и 2 000 000 ячеек. */
    public TableExporter() {
        this(DEFAULT_MAX_COLUMNS, DEFAULT_MAX_CELLS);
    }

    /**
     * Экспортёр с лимитами размера таблицы.
     *
     * @param maxColumns наибольшее число колонок
     * @param maxCells   наибольшее число ячеек данных (строки × колонки)
     */
    public TableExporter(int maxColumns, long maxCells) {
        if (maxColumns <= 0 || maxCells <= 0) {
            throw new IllegalArgumentException("Лимиты числа колонок и ячеек экспорта должны быть больше нуля");
        }
        this.maxColumns = maxColumns;
        this.maxCells = maxCells;
    }

    /** Книга XLSX с одним листом, названным по {@link TableData#title()}. */
    public byte[] toXlsx(TableData data) {
        validate(data);
        requireWithinLimits(data);
        List<Column> columns = data.columns();
        try (SXSSFWorkbook book = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(WorkbookUtil.createSafeSheetName(data.title()));
            Font bold = book.createFont();
            bold.setBold(true);
            CellStyle headerStyle = book.createCellStyle();
            headerStyle.setFont(bold);
            CellStyle dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"));
            CellStyle dateTimeStyle = book.createCellStyle();
            dateTimeStyle.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy hh:mm"));

            int[] widths = new int[columns.size()];
            Row header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(truncate(columns.get(c).label()));
                cell.setCellStyle(headerStyle);
                widths[c] = columns.get(c).label().length();
            }
            int r = 1;
            for (Map<String, Object> values : data.rows()) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < columns.size(); c++) {
                    Column column = columns.get(c);
                    Object value = values.get(column.key());
                    if (value == null) continue;
                    writeCell(row.createCell(c), column.type(), value, dateStyle, dateTimeStyle);
                    widths[c] = Math.max(widths[c], format(column.type(), value).length());
                }
            }
            for (int c = 0; c < columns.size(); c++) {
                sheet.setColumnWidth(c, (Math.min(Math.max(widths[c], 4), MAX_COLUMN_CHARS) + 2) * 256);
            }
            sheet.createFreezePane(0, 1);
            book.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось сформировать XLSX", exception);
        }
    }

    /** CSV: UTF-8 с BOM, {@code ;}, CRLF; первая строка — подписи колонок. */
    public byte[] toCsv(TableData data) {
        validate(data);
        requireWithinLimits(data);
        List<Column> columns = data.columns();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(';').setRecordSeparator("\r\n").get();
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, format)) {
            writer.write('﻿');
            printer.printRecord(columns.stream().map(column -> escapeFormula(column.label())).toList());
            for (Map<String, Object> values : data.rows()) {
                printer.printRecord(columns.stream()
                        .map(column -> csvValue(column.type(), values.get(column.key())))
                        .toList());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось сформировать CSV", exception);
        }
        return out.toByteArray();
    }

    /** DOCX: заголовок и таблица с жирной шапкой. */
    public byte[] toDocx(TableData data) {
        validate(data);
        requireWithinLimits(data);
        List<Column> columns = data.columns();
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph();
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(14);
            titleRun.setText(xmlSafe(data.title()));

            XWPFTable table = document.createTable(data.rows().size() + 1, columns.size());
            table.setWidth("100%");
            for (int c = 0; c < columns.size(); c++) {
                cellText(table.getRow(0).getCell(c), columns.get(c).label(), true);
            }
            for (int r = 0; r < data.rows().size(); r++) {
                Map<String, Object> values = data.rows().get(r);
                for (int c = 0; c < columns.size(); c++) {
                    Column column = columns.get(c);
                    Object value = values.get(column.key());
                    cellText(table.getRow(r + 1).getCell(c), value == null ? "" : format(column.type(), value),
                            false);
                }
            }
            document.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось сформировать DOCX", exception);
        }
    }

    /** Проверяет колонки: есть хотя бы одна, ключи непустые и не повторяются. */
    public static void validate(TableData data) {
        if (data == null || data.columns().isEmpty()) {
            throw new IllegalArgumentException("Таблица экспорта должна содержать колонки");
        }
        Set<String> keys = new HashSet<>();
        for (Column column : data.columns()) {
            if (column == null || column.key() == null || column.key().isBlank()) {
                throw new IllegalArgumentException("Ключ колонки не может быть пустым");
            }
            if (!keys.add(column.key())) {
                throw new IllegalArgumentException("Повторяющийся ключ колонки: " + column.key());
            }
        }
    }

    /**
     * Проверяет размер таблицы: не больше {@code maxColumns} колонок и {@code maxCells} ячеек данных
     * (строки × колонки).
     *
     * @throws IllegalArgumentException если таблица больше лимитов (400)
     */
    public void requireWithinLimits(TableData data) {
        int columns = data.columns().size();
        if (columns > maxColumns) {
            throw new IllegalArgumentException("Слишком много колонок: " + columns
                    + ", допустимо не более " + maxColumns);
        }
        long cells = (long) data.rows().size() * columns;
        if (cells > maxCells) {
            throw new IllegalArgumentException("Слишком много ячеек: " + cells
                    + ", допустимо не более " + maxCells);
        }
    }

    private static void writeCell(Cell cell, ColumnType type, Object value, CellStyle dateStyle,
                                  CellStyle dateTimeStyle) {
        switch (type) {
            case NUMBER -> {
                BigDecimal number = number(value);
                if (number != null) {
                    cell.setCellValue(number.doubleValue());
                    return;
                }
            }
            case DATE -> {
                LocalDate date = date(value);
                if (date != null && !date.isBefore(EXCEL_EPOCH)) {
                    cell.setCellValue(date);
                    cell.setCellStyle(dateStyle);
                    return;
                }
            }
            case DATETIME -> {
                LocalDateTime dateTime = dateTime(value);
                if (dateTime != null && !dateTime.toLocalDate().isBefore(EXCEL_EPOCH)) {
                    cell.setCellValue(dateTime);
                    cell.setCellStyle(dateTimeStyle);
                    return;
                }
            }
            default -> { /* Текст и логические значения пишутся строкой. */ }
        }
        cell.setCellValue(truncate(format(type, value)));
    }

    private static String csvValue(ColumnType type, Object value) {
        if (value == null) return "";
        String text = format(type, value);
        // Настоящее число вроде «-5» безопасно и должно остаться числом.
        if (type == ColumnType.NUMBER && number(value) != null) return text;
        return escapeFormula(text);
    }

    /** Префикс {@code '} для текста, который табличный редактор принял бы за формулу. */
    static String escapeFormula(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        return FORMULA_PREFIXES.indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }

    /** Текстовое представление значения по типу колонки; неприводимое значение — {@code toString()}. */
    public static String format(ColumnType type, Object value) {
        if (value == null) return "";
        switch (type) {
            case NUMBER -> {
                BigDecimal number = number(value);
                if (number != null) return number.toPlainString().replace('.', ',');
            }
            case DATE -> {
                LocalDate date = date(value);
                if (date != null) return DATE.format(date);
            }
            case DATETIME -> {
                LocalDateTime dateTime = dateTime(value);
                if (dateTime != null) return DATE_TIME.format(dateTime);
            }
            case BOOLEAN -> {
                Boolean bool = bool(value);
                if (bool != null) return bool ? "Да" : "Нет";
            }
            default -> { /* TEXT */ }
        }
        return value instanceof Enum<?> constant ? constant.name() : String.valueOf(value);
    }

    /** Число из значения ячейки ({@code Number} или строка с точкой/запятой); не число — {@code null}. */
    public static BigDecimal number(Object value) {
        return switch (value) {
            case BigDecimal decimal -> decimal;
            case BigInteger integer -> new BigDecimal(integer);
            case Double d when d.isNaN() || d.isInfinite() -> null;
            case Float f when f.isNaN() || f.isInfinite() -> null;
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> new BigDecimal(f.toString());
            case Number n -> BigDecimal.valueOf(n.longValue());
            case String s -> parseNumber(s);
            default -> null;
        };
    }

    private static BigDecimal parseNumber(String text) {
        String normalized = text.strip().replace(" ", "").replace(" ", "").replace(',', '.');
        if (normalized.isEmpty()) return null;
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Дата из значения ячейки (дата, дата-время, ISO или {@code dd.MM.yyyy}); не дата — {@code null}. */
    public static LocalDate date(Object value) {
        return switch (value) {
            case LocalDate date -> date;
            case LocalDateTime dateTime -> dateTime.toLocalDate();
            case java.sql.Date sqlDate -> sqlDate.toLocalDate();
            case String text -> {
                LocalDate parsed = parse(text.strip(), LocalDate::parse);
                if (parsed == null) parsed = parse(text.strip(), s -> LocalDate.parse(s, DATE));
                if (parsed == null) {
                    LocalDateTime dateTime = dateTime(text);
                    parsed = dateTime == null ? null : dateTime.toLocalDate();
                }
                yield parsed;
            }
            default -> {
                LocalDateTime dateTime = dateTime(value);
                yield dateTime == null ? null : dateTime.toLocalDate();
            }
        };
    }

    /** Дата и время из значения ячейки в часовом поясе JVM; не дата — {@code null}. */
    public static LocalDateTime dateTime(Object value) {
        ZoneId zone = ZoneId.systemDefault();
        return switch (value) {
            case LocalDateTime dateTime -> dateTime;
            case LocalDate date -> date.atStartOfDay();
            case Instant instant -> LocalDateTime.ofInstant(instant, zone);
            case OffsetDateTime offset -> offset.atZoneSameInstant(zone).toLocalDateTime();
            case ZonedDateTime zoned -> zoned.withZoneSameInstant(zone).toLocalDateTime();
            case java.sql.Date sqlDate -> sqlDate.toLocalDate().atStartOfDay();
            case java.util.Date date -> LocalDateTime.ofInstant(date.toInstant(), zone);
            case String text -> {
                String s = text.strip();
                LocalDateTime parsed = parse(s, LocalDateTime::parse);
                if (parsed == null) parsed = parse(s, t -> LocalDateTime.parse(t, DATE_TIME));
                if (parsed == null) {
                    OffsetDateTime offset = parse(s, OffsetDateTime::parse);
                    if (offset != null) parsed = offset.atZoneSameInstant(zone).toLocalDateTime();
                }
                if (parsed == null) {
                    LocalDate date = parse(s, LocalDate::parse);
                    if (date != null) parsed = date.atStartOfDay();
                }
                yield parsed;
            }
            default -> null;
        };
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) {
            String s = text.strip().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("да")) return true;
            if (s.equals("false") || s.equals("нет")) return false;
        }
        return null;
    }

    private static <T> T parse(String text, java.util.function.Function<String, T> parser) {
        try {
            return parser.apply(text);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static void cellText(XWPFTableCell cell, String text, boolean bold) {
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().getFirst();
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setText(xmlSafe(text));
    }

    private static String truncate(String text) {
        return text.length() > MAX_CELL_TEXT ? text.substring(0, MAX_CELL_TEXT) : text;
    }

    /** Убирает управляющие символы, недопустимые в XML документа Word. */
    private static String xmlSafe(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') result.append(ch);
        }
        return result.toString();
    }
}
