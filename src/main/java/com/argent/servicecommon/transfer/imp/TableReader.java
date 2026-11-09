package com.argent.servicecommon.transfer.imp;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Читает листы XLSX и файлы CSV в строки «заголовок → значение» с номерами строк Excel, без учёта локали;
 * CSV — UTF-8 (BOM допускается), иначе windows-1251, разделитель по строке заголовков.
 */
public class TableReader {

    /** Сообщение при превышении лимита размера файла. */
    public static final String TOO_LARGE = "Файл превышает допустимый размер";

    /** Сообщение при превышении лимита числа строк листа. */
    public static final String TOO_MANY_ROWS = "Лист превышает допустимое число строк";

    /** Сообщение при превышении лимита суммарного размера распакованных элементов XLSX. */
    public static final String TOO_LARGE_UNZIPPED = "Распакованный XLSX превышает допустимый размер";

    private static final long DEFAULT_MAX_BYTES = 20_971_520L;
    private static final int DEFAULT_MAX_ROWS = 100_000;
    private static final long MIN_ENTRY_SIZE = 10L * 1024 * 1024;
    /** Распакованная часть XLSX не может быть больше файла более чем в 8 раз. */
    private static final long MAX_ENTRY_SIZE_FACTOR = 8;
    /** Наибольший размер элемента ZIP, который принимает {@link ZipSecureFile#setMaxEntrySize}. */
    private static final long POI_MAX_ENTRY_SIZE = 0xFFFFFFFFL;
    /** Первые символы, перед которыми экспорт CSV ставит {@code '} (см. {@code TableExporter.escapeFormula}). */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    private final long maxBytes;
    private final int maxRows;
    private final long maxEntrySize;

    /** Читатель с лимитами 20 МБ и 100 000 строк на лист. */
    public TableReader() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_ROWS);
    }

    /** Читатель с указанным лимитом размера файла в байтах и 100 000 строк на лист. */
    public TableReader(long maxBytes) {
        this(maxBytes, DEFAULT_MAX_ROWS);
    }

    /** Читатель с лимитами размера файла в байтах и числа строк данных на лист. */
    public TableReader(long maxBytes, int maxRows) {
        if (maxBytes <= 0 || maxRows <= 0) {
            throw new IllegalArgumentException("Лимиты размера файла и числа строк должны быть больше нуля");
        }
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
        this.maxEntrySize = maxEntrySize(maxBytes);
    }

    /** Читает все листы книги XLSX в порядке файла. */
    public List<SheetData> readXlsx(InputStream input) {
        byte[] bytes = readLimited(input);
        // Защита от zip-бомбы: сумма распакованных элементов проверяется до разбора книги, каждый
        // элемент дополнительно ограничен ZipSecureFile; минимальная степень сжатия остаётся по
        // умолчанию POI (0.01). Настройка ZipSecureFile глобальна для JVM. Проверка и POI читают один и тот же
        // временный файл через центральный каталог ZIP: элемент, которого нет в каталоге, не видит никто.
        Path temp = null;
        OPCPackage pkg = null;
        try {
            temp = Files.createTempFile("argent-import-", ".xlsx");
            Files.write(temp, bytes);
            requireUnzippedWithinLimit(temp);
            ZipSecureFile.setMaxEntrySize(maxEntrySize);
            pkg = OPCPackage.open(temp.toFile(), PackageAccess.READ);
            Workbook workbook = new XSSFWorkbook(pkg);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<SheetData> sheets = new ArrayList<>();
            for (Sheet sheet : workbook) {
                sheets.add(readSheet(sheet, evaluator, formatter));
            }
            return List.copyOf(sheets);
        } catch (LimitExceededException exception) {
            throw exception;
        } catch (IOException | InvalidFormatException | RuntimeException exception) {
            throw new IllegalArgumentException("Не удалось прочитать файл XLSX", exception);
        } finally {
            if (pkg != null) {
                // Пакет открыт только для чтения: revert() закрывает файл без попытки сохранения.
                pkg.revert();
            }
            deleteQuietly(temp);
        }
    }

    /** Читает файл CSV как один лист с именем {@code CSV}. */
    public SheetData readCsv(InputStream input) {
        String text = decode(readLimited(input));
        String headerLine = text.lines().findFirst().orElse("");
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter(headerLine))
                .setIgnoreEmptyLines(true)
                .get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<String> headers = null;
            List<RowData> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (headers == null) {
                    headers = uniqueHeaders(record.stream().map(value -> clean(unescapeFormula(value))).toList());
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String header = headers.get(i);
                    if (header.isEmpty()) continue;
                    values.put(header, i < record.size() ? clean(unescapeFormula(record.get(i))) : "");
                }
                RowData row = new RowData((int) record.getRecordNumber(), values);
                if (!row.blank()) rows.add(row);
                if (rows.size() > maxRows) {
                    throw new LimitExceededException(TOO_MANY_ROWS);
                }
            }
            return new SheetData("CSV", headers == null ? List.of() : withoutBlank(headers), rows);
        } catch (LimitExceededException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Не удалось прочитать файл CSV", exception);
        }
    }

    private SheetData readSheet(Sheet sheet, FormulaEvaluator evaluator, DataFormatter formatter) {
        Row headerRow = sheet.getPhysicalNumberOfRows() == 0 ? null : sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            return new SheetData(sheet.getSheetName(), List.of(), List.of());
        }
        List<String> raw = new ArrayList<>();
        for (int c = 0; c < Math.max(headerRow.getLastCellNum(), 0); c++) {
            raw.add(clean(cellText(headerRow.getCell(c), evaluator, formatter)));
        }
        List<String> headers = uniqueHeaders(raw);
        // Проверка до чтения ячеек: физические строки без заголовка.
        if (sheet.getPhysicalNumberOfRows() - 1 > maxRows) {
            throw new LimitExceededException(TOO_MANY_ROWS);
        }
        List<RowData> rows = new ArrayList<>();
        for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                if (headers.get(c).isEmpty()) continue;
                values.put(headers.get(c), clean(cellText(row.getCell(c), evaluator, formatter)));
            }
            RowData data = new RowData(r + 1, values);
            if (!data.blank()) rows.add(data);
        }
        return new SheetData(sheet.getSheetName(), withoutBlank(headers), rows);
    }

    private static String cellText(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            CellValue value;
            try {
                value = evaluator.evaluate(cell);
            } catch (RuntimeException exception) {
                // Неподдерживаемая функция: используем значение, сохранённое Excel.
                return cachedFormulaText(cell, formatter);
            }
            if (value == null) return "";
            return switch (value.getCellType()) {
                case NUMERIC -> numericText(cell, value.getNumberValue());
                case STRING -> value.getStringValue();
                case BOOLEAN -> Boolean.toString(value.getBooleanValue());
                default -> "";
            };
        }
        return switch (type) {
            case NUMERIC -> numericText(cell, cell.getNumericCellValue());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case BLANK -> "";
            default -> formatter.formatCellValue(cell);
        };
    }

    private static String cachedFormulaText(Cell cell, DataFormatter formatter) {
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> numericText(cell, cell.getNumericCellValue());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> formatter.formatCellValue(cell);
        };
    }

    /** Дата — ISO, число — без группировки и экспоненты, независимо от формата ячейки. */
    private static String numericText(Cell cell, double value) {
        if (DateUtil.isCellDateFormatted(cell) && DateUtil.isValidExcelDate(value)) {
            LocalDateTime dateTime = DateUtil.getLocalDateTime(value);
            return dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ? dateTime.toLocalDate().toString() : dateTime.toString();
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) return "";
        BigDecimal number = BigDecimal.valueOf(value).stripTrailingZeros();
        return number.signum() == 0 ? "0" : number.toPlainString();
    }

    /**
     * Лимит распакованного XLSX (суммарно и для каждого элемента): {@code maxBytes * 8}, но не меньше 10 МБ. Значение вне
     * диапазона POI (больше 4 ГБ, то есть {@code max-upload-bytes} больше 512 МБ) — ошибка конфигурации,
     * обнаруживается при создании бина.
     */
    private static long maxEntrySize(long maxBytes) {
        long size;
        try {
            size = Math.max(Math.multiplyExact(maxBytes, MAX_ENTRY_SIZE_FACTOR), MIN_ENTRY_SIZE);
        } catch (ArithmeticException exception) {
            size = Long.MAX_VALUE;
        }
        if (size > POI_MAX_ENTRY_SIZE) {
            throw new IllegalStateException("argent.transfer.max-upload-bytes слишком велик: не более "
                    + POI_MAX_ENTRY_SIZE / MAX_ENTRY_SIZE_FACTOR + " байт (распакованный XLSX ограничен 4 ГБ)");
        }
        return size;
    }

    /**
     * Распаковывает все элементы ZIP в никуда и проверяет, что их суммарный размер не больше
     * {@code maxBytes * 8} (не меньше 10 МБ). Элементы берутся из центрального каталога ({@link ZipFile}) — того же,
     * по которому книгу затем открывает POI, — поэтому принимаются элементы с дескриптором данных (POI SXSSF и
     * другие потоковые генераторы), а элементы вне каталога игнорируются обоими. Считаются фактически
     * распакованные байты, а не размеры из каталога. Повреждённый ZIP → «Не удалось прочитать файл XLSX».
     */
    private void requireUnzippedWithinLimit(Path file) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (entry.getSize() > maxEntrySize) {
                    throw new LimitExceededException(TOO_LARGE_UNZIPPED);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                        if (total > maxEntrySize) {
                            throw new LimitExceededException(TOO_LARGE_UNZIPPED);
                        }
                    }
                }
            }
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            file.toFile().deleteOnExit();
        }
    }

    private byte[] readLimited(InputStream input) {
        try (input) {
            byte[] bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, maxBytes + 1));
            if (bytes.length > maxBytes) {
                throw new LimitExceededException(TOO_LARGE);
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Не удалось прочитать файл", exception);
        }
    }

    private static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer).toString();
        } catch (CharacterCodingException exception) {
            // Excel в русской локали сохраняет CSV в windows-1251.
            return new String(bytes, offset, bytes.length - offset, WINDOWS_1251);
        }
    }

    private static char delimiter(String headerLine) {
        long semicolons = headerLine.chars().filter(ch -> ch == ';').count();
        long commas = headerLine.chars().filter(ch -> ch == ',').count();
        long tabs = headerLine.chars().filter(ch -> ch == '\t').count();
        if (tabs > semicolons && tabs > commas) return '\t';
        return semicolons >= commas && semicolons > 0 ? ';' : ',';
    }

    /** Пустые заголовки остаются пустыми (колонка игнорируется), повторы получают суффикс « (2)». */
    private static List<String> uniqueHeaders(List<String> raw) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String header : raw) {
            if (header.isEmpty()) {
                result.add("");
                continue;
            }
            int count = seen.merge(header, 1, Integer::sum);
            result.add(count == 1 ? header : header + " (" + count + ")");
        }
        return result;
    }

    private static List<String> withoutBlank(List<String> headers) {
        return headers.stream().filter(header -> !header.isEmpty()).toList();
    }

    /** Превышен лимит размера или числа строк; сообщение передаётся пользователю без обёртки. */
    private static final class LimitExceededException extends IllegalArgumentException {
        LimitExceededException(String message) {
            super(message);
        }
    }

    /**
     * Снимает префикс {@code '}, который экспорт CSV ставит перед значением, начинающимся с {@code = + - @}, табуляции
     * или возврата каретки (защита от формул), а также перед значением, которое само выглядит экранированным
     * ({@code '+x} экспортируется как {@code ''+x}). Обратная операция к {@code TableExporter.escapeFormula}.
     */
    static String unescapeFormula(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '\'') return value;
        String rest = value.substring(1);
        return startsWithFormula(rest) || rest.charAt(0) == '\'' && rest.length() > 1
                && startsWithFormula(rest.substring(1)) ? rest : value;
    }

    private static boolean startsWithFormula(String text) {
        return !text.isEmpty() && FORMULA_PREFIXES.indexOf(text.charAt(0)) >= 0;
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
