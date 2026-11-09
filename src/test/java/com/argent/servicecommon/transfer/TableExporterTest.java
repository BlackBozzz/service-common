package com.argent.servicecommon.transfer;

import com.argent.servicecommon.transfer.exp.TableData;
import com.argent.servicecommon.transfer.exp.TableData.Column;
import com.argent.servicecommon.transfer.exp.TableData.ColumnType;
import com.argent.servicecommon.transfer.exp.TableExporter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Выгрузка {@link TableData} в XLSX, CSV и DOCX: типы ячеек, защита от CSV-инъекций, шапка. */
class TableExporterTest {

    private final TableExporter exporter = new TableExporter();

    private static TableData sample() {
        List<Column> columns = List.of(
                new Column("name", "Название", ColumnType.TEXT),
                new Column("population", "Население", ColumnType.NUMBER),
                new Column("founded", "Дата основания", ColumnType.DATE),
                new Column("updated", "Изменено", ColumnType.DATETIME),
                new Column("active", "Активна", ColumnType.BOOLEAN));
        Map<String, Object> first = new HashMap<>();
        first.put("name", "Франция");
        first.put("population", 68_000_000);
        first.put("founded", LocalDate.of(1958, 10, 4));
        first.put("updated", LocalDateTime.of(2026, 11, 7, 10, 30));
        first.put("active", true);
        Map<String, Object> second = new HashMap<>();
        second.put("name", "Чили");
        second.put("population", new BigDecimal("1234.5"));
        second.put("founded", "1910-09-18");
        second.put("active", false);
        return new TableData("Страны", columns, List.of(first, second));
    }

    @Test
    void xlsxShouldUseNativeCellTypesAndBoldHeader() throws Exception {
        byte[] bytes = exporter.toXlsx(sample());

        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Страны");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Название");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Активна");
            assertThat(book.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(sheet.getPaneInformation()).isNotNull();
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();

            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("Франция");
            Cell population = row.getCell(1);
            assertThat(population.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(population.getNumericCellValue()).isEqualTo(68_000_000d);
            Cell founded = row.getCell(2);
            assertThat(founded.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(founded)).isTrue();
            assertThat(founded.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(1958, 10, 4));
            assertThat(founded.getCellStyle().getDataFormatString()).isEqualTo("dd.mm.yyyy");
            Cell updated = row.getCell(3);
            assertThat(DateUtil.isCellDateFormatted(updated)).isTrue();
            assertThat(updated.getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 11, 7, 10, 30));
            assertThat(updated.getCellStyle().getDataFormatString()).isEqualTo("dd.mm.yyyy hh:mm");
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("Да");

            Row next = sheet.getRow(2);
            assertThat(next.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(next.getCell(1).getNumericCellValue()).isEqualTo(1234.5d);
            assertThat(next.getCell(2).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(LocalDate.of(1910, 9, 18));
            assertThat(next.getCell(3) == null || next.getCell(3).getCellType() == CellType.BLANK).isTrue();
            assertThat(next.getCell(4).getStringCellValue()).isEqualTo("Нет");
            assertThat(sheet.getColumnWidth(0)).isGreaterThan(0).isLessThanOrEqualTo(255 * 256);
        }
    }

    @Test
    void xlsxShouldWriteDatesBefore1900AsText() throws Exception {
        TableData data = new TableData("Даты", List.of(new Column("d", "Дата", ColumnType.DATE)),
                List.of(Map.of("d", LocalDate.of(1789, 7, 14)), Map.of("d", "не дата")));

        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(exporter.toXlsx(data)))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("14.07.1789");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("не дата");
        }
    }

    @Test
    void csvShouldHaveBomSemicolonAndFormattedValues() throws Exception {
        byte[] bytes = exporter.toCsv(sample());

        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).startsWith("Название;Население;Дата основания;Изменено;Активна\r\n");
        List<CSVRecord> records = CSVFormat.DEFAULT.builder().setDelimiter(';').get()
                .parse(new StringReader(text)).getRecords();
        assertThat(records).hasSize(3);
        assertThat(records.get(1).toList())
                .containsExactly("Франция", "68000000", "04.10.1958", "07.11.2026 10:30", "Да");
        assertThat(records.get(2).toList()).containsExactly("Чили", "1234,5", "18.09.1910", "", "Нет");
    }

    @Test
    void csvShouldNeutralizeFormulaInjection() throws Exception {
        List<Column> columns = List.of(new Column("value", "Значение", ColumnType.TEXT),
                new Column("amount", "Сумма", ColumnType.NUMBER));
        List<Map<String, Object>> rows = List.of(
                Map.of("value", "=1+1", "amount", -5),
                Map.of("value", "+1", "amount", "-2+3"),
                Map.of("value", "-1", "amount", 0),
                Map.of("value", "@SUM(A1)", "amount", 0),
                Map.of("value", "\tcmd", "amount", 0),
                Map.of("value", "\rcmd", "amount", 0),
                Map.of("value", "обычный текст", "amount", 0),
                Map.of("value", "'+x", "amount", 0),
                Map.of("value", "'текст", "amount", 0));

        String text = new String(exporter.toCsv(new TableData("Инъекции", columns, rows)), StandardCharsets.UTF_8)
                .substring(1);
        List<CSVRecord> records = CSVFormat.DEFAULT.builder().setDelimiter(';').get()
                .parse(new StringReader(text)).getRecords();

        assertThat(records.get(1).get(0)).isEqualTo("'=1+1");
        assertThat(records.get(1).get(1)).isEqualTo("-5");
        assertThat(records.get(2).get(0)).isEqualTo("'+1");
        assertThat(records.get(2).get(1)).isEqualTo("'-2+3");
        assertThat(records.get(3).get(0)).isEqualTo("'-1");
        assertThat(records.get(4).get(0)).isEqualTo("'@SUM(A1)");
        assertThat(records.get(5).get(0)).isEqualTo("'\tcmd");
        assertThat(records.get(6).get(0)).isEqualTo("'\rcmd");
        assertThat(records.get(7).get(0)).isEqualTo("обычный текст");
        // Значение, которое само выглядит как экранированное, экранируется ещё раз: импорт снимет ровно один «'».
        assertThat(records.get(8).get(0)).isEqualTo("''+x");
        assertThat(records.get(9).get(0)).isEqualTo("'текст");
    }

    @Test
    void docxShouldContainTitleAndTableWithHeader() throws Exception {
        byte[] bytes = exporter.toDocx(sample());

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(document.getParagraphs()).anySatisfy(paragraph ->
                    assertThat(paragraph.getText()).isEqualTo("Страны"));
            XWPFTable table = document.getTables().getFirst();
            assertThat(table.getRow(0).getTableCells().stream().map(XWPFTableCell::getText).toList())
                    .containsExactly("Название", "Население", "Дата основания", "Изменено", "Активна");
            assertThat(table.getRow(0).getCell(0).getParagraphs().getFirst().getRuns().getFirst().isBold())
                    .isTrue();
            assertThat(table.getRow(1).getTableCells().stream().map(XWPFTableCell::getText).toList())
                    .containsExactly("Франция", "68000000", "04.10.1958", "07.11.2026 10:30", "Да");
            assertThat(table.getRows()).hasSize(3);
        }
    }

    @Test
    void shouldRejectTableWithoutColumnsOrWithDuplicateKeys() {
        assertThatThrownBy(() -> exporter.toCsv(new TableData("Т", List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Таблица экспорта должна содержать колонки");
        List<Column> duplicate = List.of(new Column("a", "A", ColumnType.TEXT), new Column("a", "B", null));
        assertThatThrownBy(() -> exporter.toXlsx(new TableData("Т", duplicate, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Повторяющийся ключ колонки: a");
    }

    @Test
    void shouldRejectTooManyColumnsByDefault() {
        List<Column> columns = IntStream.range(0, 1001)
                .mapToObj(i -> new Column("c" + i, "К" + i, ColumnType.TEXT)).toList();
        TableData data = new TableData("Т", columns, List.of());

        assertThatThrownBy(() -> exporter.toXlsx(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Слишком много колонок: 1001, допустимо не более 1000");
    }

    @Test
    void shouldRejectTooManyColumnsAndCellsWithCustomLimits() {
        TableExporter limited = new TableExporter(4, 10);
        TableData wide = sample();
        assertThatThrownBy(() -> limited.toCsv(wide))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Слишком много колонок: 5, допустимо не более 4");

        List<Column> columns = List.of(new Column("a", "A", ColumnType.TEXT), new Column("b", "B", ColumnType.TEXT),
                new Column("c", "C", ColumnType.TEXT));
        List<Map<String, Object>> rows = IntStream.range(0, 4).<Map<String, Object>>mapToObj(i -> Map.of("a", i)).toList();
        TableData tall = new TableData("Т", columns, rows);
        assertThatThrownBy(() -> limited.toDocx(tall))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Слишком много ячеек: 12, допустимо не более 10");
        assertThat(limited.toXlsx(new TableData("Т", columns, rows.subList(0, 3)))).isNotEmpty();
    }

    @Test
    void shouldRejectInvalidLimits() {
        assertThatThrownBy(() -> new TableExporter(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableExporter(10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
