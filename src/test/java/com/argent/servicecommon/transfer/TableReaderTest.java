package com.argent.servicecommon.transfer;

import com.argent.servicecommon.transfer.imp.RowData;
import com.argent.servicecommon.transfer.imp.SheetData;
import com.argent.servicecommon.transfer.imp.TableReader;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Чтение листов XLSX и CSV в строки с номерами строк Excel. */
class TableReaderTest {

    private static byte[] workbook;
    private static byte[] csvWithBom;
    private static byte[] csvWindows1251;

    @BeforeAll
    static void createFiles() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("dd.mm.yyyy"));
            CellStyle dateTimeStyle = book.createCellStyle();
            dateTimeStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("dd.mm.yyyy hh:mm"));

            Sheet countries = book.createSheet("Страны");
            Row header = countries.createRow(0);
            header.createCell(0).setCellValue("Название");
            header.createCell(1).setCellValue("Население");
            header.createCell(2).setCellValue("Дата");
            header.createCell(3).setCellValue("Активна");
            header.createCell(4).setCellValue("Сумма");
            Row first = countries.createRow(1);
            first.createCell(0).setCellValue("  Франция ");
            first.createCell(1).setCellValue(68_000_000);
            first.createCell(2).setCellValue(LocalDate.of(2025, 12, 31));
            first.getCell(2).setCellStyle(dateStyle);
            first.createCell(3).setCellValue(true);
            first.createCell(4).setCellFormula("B2/1000");
            // Строка 3 Excel пустая и пропускается; номер следующей строки сохраняется.
            countries.createRow(2);
            Row third = countries.createRow(3);
            third.createCell(0).setCellValue("Чили");
            third.createCell(1).setCellValue(1234.5);
            third.createCell(2).setCellValue(LocalDateTime.of(2025, 1, 2, 10, 15));
            third.getCell(2).setCellStyle(dateTimeStyle);

            Sheet regions = book.createSheet("Регионы");
            regions.createRow(0).createCell(0).setCellValue("Название");
            regions.createRow(1).createCell(0).setCellValue("Европа");

            book.write(out);
            workbook = out.toByteArray();
        }
        String csv = "Название;Население;Комментарий\r\nФранция;\"1 234,50\";\"многострочный\nтекст\"\r\n;;\r\nЧили;19;\r\n";
        ByteArrayOutputStream bom = new ByteArrayOutputStream();
        bom.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        bom.write(csv.getBytes(StandardCharsets.UTF_8));
        csvWithBom = bom.toByteArray();
        csvWindows1251 = "Название,Код\nФранция,FR\n".getBytes(Charset.forName("windows-1251"));
    }

    @Test
    void shouldReadXlsxAllSheetsWithRowNumbers() {
        List<SheetData> sheets = new TableReader().readXlsx(new ByteArrayInputStream(workbook));

        assertThat(sheets).extracting(SheetData::name).containsExactly("Страны", "Регионы");
        SheetData countries = sheets.getFirst();
        assertThat(countries.headers()).containsExactly("Название", "Население", "Дата", "Активна", "Сумма");
        assertThat(countries.rows()).extracting(RowData::excelRow).containsExactly(2, 4);
        assertThat(countries.rows().getFirst().values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Название", "Франция", "Население", "68000000", "Дата", "2025-12-31",
                "Активна", "true", "Сумма", "68000"));
        assertThat(countries.rows().get(1).values())
                .containsEntry("Население", "1234.5")
                .containsEntry("Дата", "2025-01-02T10:15")
                .containsEntry("Активна", "")
                .containsEntry("Сумма", "");
        assertThat(sheets.get(1).rows()).singleElement()
                .satisfies(row -> assertThat(row.values()).containsEntry("Название", "Европа"));
    }

    @Test
    void shouldReadCsvWithBom() {
        SheetData sheet = new TableReader().readCsv(new ByteArrayInputStream(csvWithBom));

        assertThat(sheet.headers()).containsExactly("Название", "Население", "Комментарий");
        assertThat(sheet.rows()).extracting(RowData::excelRow).containsExactly(2, 4);
        assertThat(sheet.rows().getFirst().values()).containsEntry("Название", "Франция")
                .containsEntry("Население", "1 234,50")
                .containsEntry("Комментарий", "многострочный\nтекст");
        assertThat(sheet.rows().get(1).values()).containsEntry("Название", "Чили")
                .containsEntry("Население", "19").containsEntry("Комментарий", "");
    }

    @Test
    void shouldReadCsvInWindows1251WithComma() {
        SheetData sheet = new TableReader().readCsv(new ByteArrayInputStream(csvWindows1251));

        assertThat(sheet.headers()).containsExactly("Название", "Код");
        assertThat(sheet.rows()).singleElement()
                .satisfies(row -> assertThat(row.values()).containsEntry("Название", "Франция"));
    }

    @Test
    void shouldRejectOversizedFile() {
        TableReader reader = new TableReader(16);

        assertThatThrownBy(() -> reader.readCsv(new ByteArrayInputStream(csvWithBom)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Файл превышает допустимый размер");
        assertThatThrownBy(() -> reader.readXlsx(new ByteArrayInputStream(workbook)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Файл превышает допустимый размер");
    }

    @Test
    void shouldRejectTooManyRows() {
        TableReader reader = new TableReader(1_000_000, 1);

        assertThatThrownBy(() -> reader.readXlsx(new ByteArrayInputStream(workbook)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Лист превышает допустимое число строк");
        assertThatThrownBy(() -> reader.readCsv(new ByteArrayInputStream(csvWithBom)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Лист превышает допустимое число строк");
        assertThat(new TableReader(1_000_000, 2).readCsv(new ByteArrayInputStream(csvWithBom)).rows()).hasSize(2);
    }

    @Test
    void shouldRejectOutOfRangeUploadLimitAtCreation() {
        assertThatThrownBy(() -> new TableReader(600_000_000L, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("argent.transfer.max-upload-bytes слишком велик: не более 536870911 байт "
                        + "(распакованный XLSX ограничен 4 ГБ)");
        assertThatThrownBy(() -> new TableReader(Long.MAX_VALUE, 10)).isInstanceOf(IllegalStateException.class);
        assertThat(new TableReader(536_870_911L, 10)).isNotNull();
    }

    @Test
    void shouldRejectXlsxWhoseEntriesTogetherInflateOverLimit() throws IOException {
        // Каждый элемент меньше лимита на элемент (10 МБ), но вместе они больше суммарного лимита.
        byte[] zeros = new byte[6 * 1024 * 1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : List.of("xl/a.xml", "xl/b.xml")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(zeros);
                zip.closeEntry();
            }
        }
        byte[] bomb = out.toByteArray();
        TableReader reader = new TableReader(1_000_000);

        assertThat(bomb.length).isLessThan(1_000_000);
        assertThatThrownBy(() -> reader.readXlsx(new ByteArrayInputStream(bomb)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Распакованный XLSX превышает допустимый размер");
    }

    @Test
    void shouldReadNormalWorkbookUnderUnzippedLimit() {
        List<SheetData> sheets = new TableReader(1_000_000).readXlsx(new ByteArrayInputStream(workbook));

        assertThat(sheets).extracting(SheetData::name).containsExactly("Страны", "Регионы");
    }

    @Test
    void shouldRejectCorruptXlsx() {
        assertThatThrownBy(() -> new TableReader().readXlsx(new ByteArrayInputStream(csvWithBom)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Не удалось прочитать файл XLSX");
    }
}
