package com.argent.servicecommon.explore;

import com.argent.servicecommon.audit.entity.AuditEntry;
import com.argent.servicecommon.audit.repository.AuditEntryRepository;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.argent.servicecommon.transfer.TransferProperties;
import com.argent.servicecommon.transfer.exp.TableData;
import com.argent.servicecommon.transfer.exp.TableExporter;
import com.argent.servicecommon.transfer.imp.DictionaryImportService;
import com.argent.servicecommon.transfer.imp.ImportFailedException;
import com.argent.servicecommon.transfer.imp.ImportMode;
import com.argent.servicecommon.transfer.imp.ImportReport;
import com.argent.servicecommon.transfer.imp.RowData;
import com.argent.servicecommon.transfer.imp.SheetData;
import com.argent.servicecommon.transfer.imp.ValueConverter;
import com.argent.servicecommon.transfer.impfixture.ImpCountry;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Исследовательские пробы импорта/экспорта (одноразовые). */
@SpringBootTest(classes = TransferEdgeCasesExploreTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TransferEdgeCasesExploreTest {

    static final String BASE = "/api/import/dictionaries";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final int MAX_ROWS = 300;
    static final String WRITER = "{\"tables\":{\"imp_country\":[\"read\",\"write\"],"
            + "\"imp_region\":[\"read\",\"write\"]},\"actions\":[\"import\"]}";
    static final String COLS = "select name, code, population, area, founded, active, region_id from imp_country order by name";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_explore_transfer");
        registry.add("argent.transfer.max-upload-bytes", () -> "2000000");
        registry.add("argent.transfer.max-rows", () -> String.valueOf(MAX_ROWS));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DictionaryImportService importService;
    @Autowired ValueConverter converter;
    @Autowired DynamicDictionaryQueryService queries;
    @Autowired TokenService tokenService;
    @Autowired UserRepository userRepository;
    @Autowired UserSessionRepository userSessionRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;

    final List<UUID> users = new ArrayList<>();
    final List<UUID> roles = new ArrayList<>();
    String writer;

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("insert into imp_region(name) values ('Центральный'), ('Северный')");
        writer = token(WRITER);
    }

    @AfterEach
    void cleanUp() {
        clean();
        for (UUID id : users) {
            userRoleRepository.deleteAll(userRoleRepository.findByIdUserId(id));
            userSessionRepository.deleteAll(userSessionRepository.findAll().stream()
                    .filter(s -> s.getUserId().equals(id)).toList());
            userRepository.deleteById(id);
        }
        roles.forEach(roleRepository::deleteById);
        users.clear();
        roles.clear();
    }

    void clean() {
        jdbc.update("delete from imp_country");
        jdbc.update("delete from imp_region");
        jdbc.update("delete from imp_with_collection_tag");
        jdbc.update("delete from imp_with_collection");
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------- Импорт: заголовки и чтение ----------------

    @Test
    void p01_headersCaseAndSpaces() throws Exception {
        byte[] f = xlsx(rows(List.of("  НАЗВАНИЕ  ", "насе ление", "Дата_основания"), List.of("Франция", 5, "01.02.2026")));
        String body = upload(writer, "imp_country", f, "c.xlsx", "mode", "upsert", "keyFields", "name");
        assertThat(body).contains("\"created\":1");
        assertThat(jdbc.queryForObject("select founded from imp_country", LocalDate.class)).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void p02_duplicateHeadersGiveSheetError() throws Exception {
        byte[] f = xlsx(rows(List.of("Название", "Название"), List.of("Франция", "Чили")));
        assertThat(upload(writer, "imp_country", f, "c.xlsx", "mode", "REPLACE"))
                .contains("Колонка не сопоставлена с полем: Название (2)");
    }

    @Test
    void p03_emptyHeaderAndMergedHeaderIgnored() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet s = book.createSheet("S");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Название");
            h.createCell(2).setCellValue("Население");
            s.addMergedRegion(new CellRangeAddress(0, 0, 2, 3));
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("Франция");
            r.createCell(1).setCellValue("мусор");
            r.createCell(2).setCellValue(10);
            r.createCell(3).setCellValue("мусор2");
            assertThat(upload(writer, "imp_country", bytes(book), "c.xlsx", "mode", "REPLACE")).contains("\"created\":1");
        }
    }

    @Test
    @Disabled("QA: transfer L-4, отложено")
    void p04_explicitMappingIsCaseSensitive() throws Exception {
        byte[] f = xlsx(rows(List.of("Название"), List.of("Франция")));
        String body = upload(writer, "imp_country", f, "c.xlsx", "mode", "REPLACE", "mapping", "{\"название\":\"name\"}");
        assertThat(body).contains("\"created\":1");
    }

    @Test
    @Disabled("QA: transfer L-3, отложено")
    void p05_formulaCells() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet s = book.createSheet("S");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Название");
            h.createCell(1).setCellValue("Население");
            h.createCell(2).setCellValue("Код");
            Row r = s.createRow(1);
            r.createCell(0).setCellFormula("\"Фр\"&\"анция\"");
            r.createCell(1).setCellFormula("1+1");
            r.createCell(2).setCellFormula("0.1+0.2");
            assertThat(upload(writer, "imp_country", bytes(book), "c.xlsx", "mode", "REPLACE")).contains("\"created\":1");
            Map<String, Object> row = jdbc.queryForMap("select name, population, code from imp_country");
            System.out.println("PROBE p05 " + row);
            assertThat(row.get("population")).isEqualTo(2);
            assertThat(row.get("code")).isEqualTo("0.3");
        }
    }

    @Test
    void p06_excelSerialDateInGeneralFormat() throws Exception {
        byte[] f = xlsx(rows(List.of("Название", "Дата основания"), List.of("Франция", 46054)));
        assertThat(upload(writer, "imp_country", f, "c.xlsx", "mode", "REPLACE")).contains("Некорректная дата: 46054");
    }

    @Test
    void p07_numbersVariants() throws Exception {
        String csv = "Название;Население;Площадь\nA;1 234 567;1 234,50\nB;1e5;1,234.5\nC;1,234;12\n";
        assertThat(upload(writer, "imp_country", utf8(csv), "c.csv", "mode", "REPLACE")).contains("\"created\":3");
        assertThat(jdbc.queryForList("select population from imp_country order by name", Integer.class))
                .containsExactly(1234567, 100000, 1234);
    }

    @Test
    void p08_booleans() throws Exception {
        String csv = "Название;Активна\nA;да\nB;НЕТ\nC;1\nD;0\nE;true\nF;Истина\nG;ложь\nH;yes\n";
        assertThat(upload(writer, "imp_country", utf8(csv), "c.csv", "mode", "REPLACE")).contains("\"created\":8");
        assertThat(jdbc.queryForList("select active from imp_country order by name", Boolean.class))
                .containsExactly(true, false, true, false, true, true, false, true);
    }

    @Test
    @Disabled("QA: transfer L-2, отложено")
    void p09_decimalScaleOverflowSilentlyRounded() throws Exception {
        String body = upload(writer, "imp_country", utf8("Название;Площадь\nA;1234.567\n"), "c.csv", "mode", "REPLACE");
        System.out.println("PROBE p09 stored=" + jdbc.queryForList("select area from imp_country", BigDecimal.class));
        assertThat(body).doesNotContain("\"created\":1");
    }

    @Test
    void p10_decimalPrecisionOverflowSkipRowCancelsWholeSheet() {
        SheetData sheet = sheet(List.of("Название", "Площадь"), Map.of("Название", "A", "Площадь", "1"),
                Map.of("Название", "B", "Площадь", "10000000000000"));
        assertThatThrownBy(() -> importService.importSheet("imp_country", sheet, ImportMode.UPSERT, null,
                TransferProperties.ImportOnError.SKIP_ROW))
                .isInstanceOfSatisfying(ImportFailedException.class, e -> {
                    System.out.println("PROBE p10 " + e.report());
                    assertThat(e.report().errors().getFirst().row()).isEqualTo(3);
                });
        assertThat(jdbc.queryForObject("select count(*) from imp_country", Integer.class)).isZero();
    }

    @Test
    void p11_stringLongerThanColumn() throws Exception {
        MvcResult r = uploadResult(writer, "imp_country", utf8("Название;Код\nA;" + "x".repeat(300) + "\n"), "c.csv", "mode", "REPLACE");
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        System.out.println("PROBE p11 " + r.getResponse().getStatus() + " " + body);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body).contains("Запись нарушает ограничение целостности базы данных");
    }

    @Test
    void p12_blankKeyInUpsert() throws Exception {
        assertThat(upload(writer, "imp_region", utf8("Название;Идентификатор\n;5\n"), "c.csv", "mode", "UPSERT"))
                .contains("Не заполнено ключевое поле");
    }

    @Test
    void p13_replaceDuplicateKeysInSheet() throws Exception {
        assertThat(upload(writer, "imp_country", utf8("Название\nA\nA\n"), "c.csv", "mode", "REPLACE"))
                .contains("\"row\":3").contains("ограничение целостности");
    }

    @Test
    void p14_upsertDuplicateKeysInSheet() throws Exception {
        assertThat(upload(writer, "imp_country", utf8("Название;Население\nA;1\nA;2\n"), "c.csv", "mode", "UPSERT"))
                .contains("\"created\":1").contains("\"updated\":1");
        assertThat(jdbc.queryForObject("select population from imp_country", Integer.class)).isEqualTo(2);
    }

    @Test
    void p15_nonUniqueKeyMatchesMultiple() throws Exception {
        jdbc.update("insert into imp_country(name, code) values ('A','FR'),('B','FR')");
        assertThat(upload(writer, "imp_country", utf8("Код;Население\nFR;1\n"), "c.csv", "mode", "UPSERT", "keyFields", "code"))
                .contains("Найдено несколько записей с такими значениями ключевых полей");
    }

    @Test
    void p16_keyFieldsUnknownAndEmpty() throws Exception {
        assertThat(upload(writer, "imp_country", utf8("Название\nA\n"), "c.csv", "mode", "UPSERT", "keyFields", "nope"))
                .contains("Поле не найдено в справочнике: nope");
        assertThat(upload(writer, "imp_country", utf8("Название\nA\n"), "c.csv", "mode", "UPSERT", "keyFields", " , "))
                .contains("\"created\":1");
    }

    @Test
    void p17_keyFieldIsReadOnlyId() throws Exception {
        jdbc.update("insert into imp_country(name) values ('A')");
        Long id = jdbc.queryForObject("select id from imp_country", Long.class);
        MvcResult result = uploadResult(writer, "imp_country",
                utf8("Идентификатор;Название\n" + (id + 1000) + ";B\n"), "c.csv", "mode", "UPSERT", "keyFields", "id");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Поле «id» нельзя использовать как ключ");
        assertThat(jdbc.queryForList("select id from imp_country", Long.class)).containsExactly(id);
    }

    @Test
    void p18_referenceKey() throws Exception {
        jdbc.update("insert into imp_country(name, region_id) select 'X'||g, (select id from imp_region where name='Северный') from generate_series(1,5) g");
        String body = upload(writer, "imp_country", utf8("Регион;Население\nЦентральный;1\n"), "c.csv", "mode", "UPSERT", "keyFields", "region");
        assertThat(body).contains("\"total\":1");
    }

    @Test
    void p19_mappingTwoHeadersOneField_unknownField_nullSkip() throws Exception {
        byte[] f = utf8("Название;Имя;Комментарий\nA;B;C\n");
        assertThat(upload(writer, "imp_country", f, "c.csv", "mode", "REPLACE", "mapping",
                "{\"Название\":\"name\",\"Имя\":\"name\",\"Комментарий\":null}"))
                .contains("Поле сопоставлено с несколькими колонками: name");
        assertThat(upload(writer, "imp_country", f, "c.csv", "mode", "REPLACE", "mapping",
                "{\"Название\":\"name\",\"Имя\":\"zzz\",\"Комментарий\":null}"))
                .contains("Поле не найдено в справочнике: zzz");
        assertThat(upload(writer, "imp_country", f, "c.csv", "mode", "REPLACE", "mapping",
                "{\"Название\":\"name\",\"Имя\":\"\",\"Комментарий\":null}"))
                .contains("\"created\":1");
    }

    @Test
    @Disabled("QA: transfer L-1, отложено")
    void p20_referenceLabelsCaseDuplicates() throws Exception {
        jdbc.update("insert into imp_region(name) values ('северный')");
        assertThat(upload(writer, "imp_country", utf8("Название;Регион\nA;Северный\n"), "c.csv", "mode", "REPLACE"))
                .contains("\"created\":1");
    }

    @Test
    void p21_referenceMissingAndTrailingSpaces() throws Exception {
        assertThat(upload(writer, "imp_country", utf8("Название;Регион\nA;  Центральный  \nB;Южный\n"), "c.csv", "mode", "REPLACE"))
                .contains("Не найдена запись справочника imp_region: Южный").doesNotContain("\"row\":2");
    }

    @Test
    void p22_referenceLabelWithLikeWildcards() throws Exception {
        jdbc.update("insert into imp_region(name) values ('50%_Юг')");
        assertThat(upload(writer, "imp_country", utf8("Название;Регион\nA;50%_юг\n"), "c.csv", "mode", "REPLACE"))
                .contains("\"created\":1");
    }

    @Test
    void p23_referenceToDictionaryWithoutDisplayFieldCannotResolveById() {
        jdbc.update("insert into imp_with_collection(name) values ('n1')");
        Long id = jdbc.queryForObject("select id from imp_with_collection", Long.class);
        var all = queries.options("imp_with_collection", 0, 100, null);
        var options = queries.options("imp_with_collection", 0, 100, String.valueOf(id));
        System.out.println("PROBE p23 all=" + all.items() + " searchById=" + options.items());
        assertThat(options.items()).isNotEmpty();
    }

    @Test
    void p24_replaceReferencedRows409() throws Exception {
        jdbc.update("insert into imp_country(name, region_id) values ('A', (select id from imp_region where name='Северный'))");
        MvcResult r = uploadResult(writer, "imp_region", utf8("Название\nНовый\n"), "c.csv", "mode", "REPLACE");
        System.out.println("PROBE p24 " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from imp_region", Integer.class)).isEqualTo(2);
    }

    @Test
    void p25_failAllVsSkipRowCounts() {
        SheetData sheet = sheet(List.of("Название", "Население"),
                Map.of("Название", "A", "Население", "x"), Map.of("Название", "B", "Население", "2"),
                Map.of("Название", "C", "Население", "-1"));
        ImportReport skip = importService.importSheet("imp_country", sheet, ImportMode.REPLACE, null,
                TransferProperties.ImportOnError.SKIP_ROW);
        System.out.println("PROBE p25 skip=" + skip);
        assertThat(skip.total()).isEqualTo(3);
        assertThat(skip.created()).isEqualTo(1);
        assertThat(skip.failed()).isEqualTo(2);
        assertThatThrownBy(() -> importService.importSheet("imp_country", sheet, ImportMode.REPLACE, null,
                TransferProperties.ImportOnError.FAIL_ALL))
                .isInstanceOfSatisfying(ImportFailedException.class, e -> {
                    System.out.println("PROBE p25 fail=" + e.report());
                    assertThat(e.report().failed()).isEqualTo(2);
                    assertThat(e.report().created()).isZero();
                });
    }

    // ---------------- CSV ----------------

    @Test
    void p26_csvBomCrlfQuotedNewlineTrailingEmpty() throws Exception {
        String csv = "\uFEFFНазвание,Код\r\n\"Фр,анция\",\"a\r\nb\"\r\nЧили,CL\r\n\r\n\r\n";
        assertThat(upload(writer, "imp_country", utf8(csv), "c.csv", "mode", "REPLACE")).contains("\"created\":2");
        assertThat(jdbc.queryForObject("select code from imp_country where name='Фр,анция'", String.class)).isEqualTo("a\r\nb");
    }

    @Test
    void p27_csvCp1251() throws Exception {
        byte[] f = "Название;Код\nФранция;FR\n".getBytes(Charset.forName("windows-1251"));
        assertThat(upload(writer, "imp_country", f, "c.csv", "mode", "REPLACE")).contains("\"created\":1");
        assertThat(jdbc.queryForObject("select name from imp_country", String.class)).isEqualTo("Франция");
    }

    @Test
    void p28_csvRowNumberAfterQuotedNewline() throws Exception {
        String body = upload(writer, "imp_country", utf8("Название;Население\n\"A\nA\";1\nB;x\n"), "c.csv", "mode", "REPLACE");
        assertThat(body).contains("\"row\":3");
    }

    @Test
    void p29_csvExportRoundTripPhonePlus() throws Exception {
        jdbc.update("insert into imp_country(name, code) values ('A','+7900'),('B','-x'),('C','@y')");
        byte[] csv = mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "csv")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer)).andReturn().getResponse().getContentAsByteArray();
        System.out.println("PROBE p29 export=" + new String(csv, StandardCharsets.UTF_8));
        upload(writer, "imp_country", csv, "c.csv", "mode", "UPSERT", "keyFields", "name");
        System.out.println("PROBE p29 after=" + jdbc.queryForList("select code from imp_country order by name", String.class));
        assertThat(jdbc.queryForList("select code from imp_country order by name", String.class))
                .containsExactly("+7900", "-x", "@y");
    }

    @Test
    void p30_xlsxExportRoundTripReplace() throws Exception {
        Long region = jdbc.queryForObject("select id from imp_region where name='Северный'", Long.class);
        jdbc.update("insert into imp_country(name, code, population, area, founded, active, region_id) values "
                + "('Франция','FR',68000000,1234.56,'1958-10-04',true,?),('Чили','=1+1',19000000,null,null,false,null)", region);
        byte[] x = mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "xlsx")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer)).andReturn().getResponse().getContentAsByteArray();
        List<Map<String, Object>> before = jdbc.queryForList(COLS);
        assertThat(upload(writer, "imp_country", x, "c.xlsx", "mode", "REPLACE")).contains("\"created\":2");
        assertThat(jdbc.queryForList(COLS)).isEqualTo(before);
    }

    @Test
    void p31_csvRoundTripViaCsvExport() throws Exception {
        Long region = jdbc.queryForObject("select id from imp_region where name='Северный'", Long.class);
        jdbc.update("insert into imp_country(name, code, population, area, founded, active, region_id) values "
                + "('Франция','FR',68000000,1234.56,'1958-10-04',true,?)", region);
        byte[] csv = mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "csv")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer)).andReturn().getResponse().getContentAsByteArray();
        List<Map<String, Object>> before = jdbc.queryForList(COLS);
        upload(writer, "imp_country", csv, "c.csv", "mode", "REPLACE");
        assertThat(jdbc.queryForList(COLS)).isEqualTo(before);
    }

    @Test
    void p32_tooManyRowsCsv() throws Exception {
        StringBuilder sb = new StringBuilder("Название\n");
        for (int i = 0; i <= MAX_ROWS; i++) sb.append("N").append(i).append('\n');
        assertThat(upload(writer, "imp_country", utf8(sb.toString()), "c.csv", "mode", "REPLACE"))
                .contains("Лист превышает допустимое число строк");
    }

    @Test
    @Disabled("QA: transfer L-5, отложено")
    void p33_csvNamedButXlsxContent() throws Exception {
        byte[] f = xlsx(rows(List.of("Название"), List.of("A")));
        assertThat(upload(writer, "imp_country", f, "c.csv", "mode", "REPLACE")).contains("Не удалось прочитать файл CSV");
    }

    @Test
    void p34_xlsOldFormat() throws Exception {
        try (HSSFWorkbook book = new HSSFWorkbook()) {
            book.createSheet("S").createRow(0).createCell(0).setCellValue("Название");
            assertThat(upload(writer, "imp_country", bytes(book), "c.xls", "mode", "REPLACE"))
                    .contains("Не удалось прочитать файл XLSX");
        }
    }

    @Test
    void p35_zipBombAndEmptyFile() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 20; i++) zip.write(zeros);
            zip.closeEntry();
        }
        assertThat(upload(writer, "imp_country", out.toByteArray(), "b.xlsx", "mode", "REPLACE"))
                .contains("Распакованный XLSX превышает допустимый размер");
        assertThat(upload(writer, "imp_country", new byte[0], "e.xlsx", "mode", "REPLACE"))
                .contains("Не удалось прочитать файл XLSX");
        assertThat(upload(writer, "imp_country", new byte[0], "e.csv", "mode", "REPLACE"))
                .contains("Лист не содержит данных");
    }

    @Test
    void p36_multipartWithoutFileAndWrongMode() throws Exception {
        MvcResult r = mvc.perform(multipart(BASE + "/imp_country").param("mode", "REPLACE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer)).andReturn();
        System.out.println("PROBE p36 nofile " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(writer, "imp_country", utf8("Название\nA\n"), "c.csv", "mode", "MERGE"))
                .contains("Режим импорта должен быть REPLACE или UPSERT");
        MvcResult noMode = uploadResult(writer, "imp_country", utf8("Название\nA\n"), "c.csv");
        System.out.println("PROBE p36 nomode " + noMode.getResponse().getStatus() + " " + noMode.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(noMode.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void p37_unknownAndUppercaseCode() throws Exception {
        String admin = token("{\"actions\":[\"admin\",\"import\"],\"tables\":{\"*\":[\"read\",\"write\"]}}");
        MvcResult r = uploadResult(admin, "nope", utf8("Название\nA\n"), "c.csv", "mode", "REPLACE");
        System.out.println("PROBE p37 unknown " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        MvcResult u = uploadResult(admin, "IMP_COUNTRY", utf8("Название\nA\n"), "c.csv", "mode", "REPLACE");
        System.out.println("PROBE p37 upper " + u.getResponse().getStatus() + " " + u.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(u.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void p38_security() throws Exception {
        byte[] f = utf8("Название\nA\n");
        assertThat(uploadResult(token("{\"tables\":{\"imp_country\":[\"read\",\"write\"]}}"), "imp_country", f, "c.csv", "mode", "REPLACE")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(uploadResult(token("{\"tables\":{\"imp_country\":[\"read\"]},\"actions\":[\"import\"]}"), "imp_country", f, "c.csv", "mode", "REPLACE")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(multipart(BASE + "/imp_country").file(new MockMultipartFile("file", "c.csv", "text/csv", f))
                .param("mode", "REPLACE")).andReturn().getResponse().getStatus()).isEqualTo(401);
        String noRegionRead = token("{\"tables\":{\"imp_country\":[\"write\"]},\"actions\":[\"import\"]}");
        String body = upload(noRegionRead, "imp_country", utf8("Название;Регион\nA;Северный\n"), "c.csv", "mode", "REPLACE");
        System.out.println("PROBE p38 refWithoutRead " + body);
    }

    @Test
    void p39_workbookSheetsByNameAndDuplicates() throws Exception {
        Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
        sheets.put("Страны (импорт)", rows(List.of("Название"), List.of("A")));
        sheets.put("IMP_COUNTRY", rows(List.of("Название"), List.of("B")));
        sheets.put("Неизвестный", rows(List.of("Название"), List.of("C")));
        String body = workbook(writer, xlsx(sheets), "UPSERT");
        assertThat(body).contains("Справочник для листа не найден: Неизвестный");
        assertThat(jdbc.queryForList("select name from imp_country order by name", String.class)).containsExactly("A", "B");
    }

    @Test
    void p40_workbookReplaceFkViolationIsolated() throws Exception {
        jdbc.update("insert into imp_country(name, region_id) values ('X', (select id from imp_region where name='Северный'))");
        Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
        sheets.put("imp_region", rows(List.of("Название"), List.of("Новый")));
        sheets.put("imp_country", rows(List.of("Название"), List.of("Y")));
        String body = workbook(writer, xlsx(sheets), "REPLACE");
        assertThat(body).contains("На записи справочника ссылаются другие данные");
        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Y");
    }

    @Test
    void p41_upsertPerformance() throws Exception {
        StringBuilder sb = new StringBuilder("Название;Население\n");
        for (int i = 0; i < MAX_ROWS; i++) sb.append("N").append(i).append(";").append(i).append('\n');
        byte[] f = utf8(sb.toString());
        long t0 = System.nanoTime();
        upload(writer, "imp_country", f, "c.csv", "mode", "UPSERT");
        long t1 = System.nanoTime();
        upload(writer, "imp_country", f, "c.csv", "mode", "UPSERT");
        long t2 = System.nanoTime();
        upload(writer, "imp_country", f, "c.csv", "mode", "REPLACE");
        long t3 = System.nanoTime();
        jdbc.update("update imp_country set region_id = (select id from imp_region where name='Северный'), name = 'R'||id");
        StringBuilder ref = new StringBuilder("Регион;Население\n");
        for (int i = 0; i < 20; i++) ref.append("Центральный;").append(i).append('\n');
        long t4 = System.nanoTime();
        upload(writer, "imp_country", utf8(ref.toString()), "c.csv", "mode", "UPSERT", "keyFields", "region");
        long t5 = System.nanoTime();
        System.out.printf("PROBE p41 rows=%d upsertCreate=%dms upsertUpdate=%dms replace=%dms refKey20rows=%dms%n",
                MAX_ROWS, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t5 - t4) / 1_000_000);
    }

    @Test
    void p42_valueConverterScalars() {
        assertThat(converter.convertScalar(LocalDateTime.class, "01.02.2026 10:05:07")).isEqualTo(LocalDateTime.of(2026, 2, 1, 10, 5, 7));
        assertThat(converter.convertScalar(LocalDate.class, "2026-02-01T10:00")).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(converter.convertScalar(Thread.State.class, "runnable")).isEqualTo(Thread.State.RUNNABLE);
        assertThat(converter.convertScalar(LocalDate.class, "04.10.1858")).isEqualTo(LocalDate.of(1858, 10, 4));
        // QA I-3: дата с временем в русском формате для LocalDate не принимается — так задокументировано.
        assertThatThrownBy(() -> converter.convertScalar(LocalDate.class, "01.02.2026 10:00"))
                .hasMessageStartingWith("Некорректная дата: 01.02.2026 10:00");
        // QA M-2: длинные числа и большой показатель степени отклоняются до разбора.
        assertThat(converter.convertScalar(Integer.class, "1e5")).isEqualTo(100000);
        assertThatThrownBy(() -> converter.convertScalar(BigDecimal.class, "1e-7"))
                .hasMessage("Недопустимое число: 1e-7");
        assertThatThrownBy(() -> converter.convertScalar(BigDecimal.class, "1".repeat(41)))
                .hasMessage("Недопустимое число: " + "1".repeat(41));
    }

    @Test
    void p43_bigIntegerExponentDos() {
        long t0 = System.nanoTime();
        try {
            converter.convertScalar(BigInteger.class, "1e20000000");
            throw new AssertionError("ожидалась ошибка");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("Недопустимое число: 1e20000000");
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("PROBE p43 bigInteger 1e20000000 took " + ms + "ms");
        assertThat(ms).isLessThan(1000);
    }

    @Test
    void p44_exportedXlsxReadableByTableReader() throws Exception {
        jdbc.update("insert into imp_country(name) values ('A')");
        byte[] x = mvc.perform(get("/api/export/dictionaries/imp_country").header(HttpHeaders.AUTHORIZATION, "Bearer " + writer))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] y = new TableExporter().toXlsx(new TableData("T", List.of(new TableData.Column("a", "A", null)),
                List.of(Map.of("a", "v"))));
        for (byte[] bytes : List.of(x, y)) {
            try {
                new com.argent.servicecommon.transfer.imp.TableReader().readXlsx(new ByteArrayInputStream(bytes));
                System.out.println("PROBE p44 ok");
            } catch (RuntimeException e) {
                Throwable t = e;
                StringBuilder chain = new StringBuilder();
                while (t != null) {
                    chain.append(" <- ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
                    t = t.getCause();
                }
                System.out.println("PROBE p44 fail" + chain);
                throw e;
            }
        }
    }

    @Test
    void p45_upsertScaling() {
        for (int n : List.of(500, 2000)) {
            jdbc.update("delete from imp_country");
            @SuppressWarnings("unchecked")
            Map<String, String>[] rows = new Map[n];
            for (int i = 0; i < n; i++) rows[i] = Map.of("Название", "N" + i, "Население", String.valueOf(i));
            SheetData s = sheet(List.of("Название", "Население"), rows);
            long t0 = System.nanoTime();
            importService.importSheet("imp_country", s, ImportMode.UPSERT, null);
            long t1 = System.nanoTime();
            importService.importSheet("imp_country", s, ImportMode.REPLACE, null);
            long t2 = System.nanoTime();
            System.out.printf("PROBE p45 n=%d upsert=%dms (%.2f ms/row) replace=%dms%n", n, (t1 - t0) / 1_000_000,
                    (t1 - t0) / 1e6 / n, (t2 - t1) / 1_000_000);
        }
    }

    @Test
    void p46_referenceKeyScalesWithTable() {
        Long region = jdbc.queryForObject("select id from imp_region where name='Северный'", Long.class);
        jdbc.update("insert into imp_country(name, region_id) select 'X'||g, ? from generate_series(1,3000) g", region);
        jdbc.update("insert into imp_country(name, region_id) values ('Target', (select id from imp_region where name='Центральный'))");
        @SuppressWarnings("unchecked")
        Map<String, String>[] rows = new Map[10];
        for (int i = 0; i < 10; i++) rows[i] = Map.of("Регион", "Центральный", "Население", String.valueOf(i));
        long t0 = System.nanoTime();
        ImportReport r = importService.importSheet("imp_country", sheet(List.of("Регион", "Население"), rows), ImportMode.UPSERT,
                new com.argent.servicecommon.transfer.imp.ColumnMapping(Map.of("Регион", "region", "Население", "population"), List.of("region")));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("PROBE p46 table=3001 rows=10 refKey upsert " + ms + "ms report=" + r.updated());
    }

    // ---------------- Экспорт ----------------

    @Test
    void e01_formats() throws Exception {
        assertThat(exportBody("pdf", null, null)).contains("Формат PDF не поддерживается");
        assertThat(exportBody("odt", null, null)).contains("Неизвестный формат экспорта: odt");
        assertThat(exportBody("XLSX", null, null)).isNotBlank();
    }

    @Test
    void e02_sortInjectionAndSearchWildcards() throws Exception {
        jdbc.update("insert into imp_country(name, code) values ('100%','a'),('A_B','b'),('AxB','c'),('O''Brien','d')");
        assertThat(exportBody("csv", null, "name;drop table imp_country")).contains("Сортировка разрешена только");
        assertThat(exportBody("csv", null, "bogus,asc")).contains("Сортировка разрешена только");
        assertThat(exportBody("csv", null, "region,asc")).contains("Сортировка разрешена только");
        assertThat(exportBody("csv", "%", null)).contains("100%").doesNotContain("AxB");
        assertThat(exportBody("csv", "_", null)).contains("A_B").doesNotContain("AxB");
        assertThat(exportBody("csv", "'", null)).contains("O'Brien");
    }

    @Test
    void e03_tooManyRows() throws Exception {
        jdbc.update("insert into imp_country(name) select 'N'||g from generate_series(1," + (MAX_ROWS + 1) + ") g");
        assertThat(exportBody("csv", null, null)).contains("Слишком много записей для экспорта: больше " + MAX_ROWS);
    }

    @Test
    void e04_formulaInjectionCellsAndLongText() throws Exception {
        jdbc.update("insert into imp_country(name, code) values ('=HYPERLINK(\"http://x\")', '+1'), ('@SUM(1)', ?)", "y".repeat(250));
        byte[] x = mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "xlsx").param("sort", "name,asc")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer)).andReturn().getResponse().getContentAsByteArray();
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            for (Row row : book.getSheetAt(0)) {
                row.forEach(cell -> assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA));
            }
        }
        String csv = exportBody("csv", null, "name,asc");
        assertThat(csv).contains("'=HYPERLINK").contains("'@SUM").contains("'+1");
        TableExporter exporter = new TableExporter();
        byte[] big = exporter.toXlsx(new TableData("T", List.of(new TableData.Column("a", "A", null)),
                List.of(Map.of("a", "z".repeat(40000)))));
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(big))) {
            System.out.println("PROBE e04 truncated len=" + book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue().length());
        }
    }

    @Test
    void e05_contentDispositionCyrillic() throws Exception {
        MvcResult r = mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "csv")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer)).andReturn();
        String cd = r.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        System.out.println("PROBE e05 " + cd);
        assertThat(cd).contains("filename*=UTF-8''").contains("attachment");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void e06_exportWithWriteOnlyAndUnknown() throws Exception {
        String w = token("{\"tables\":{\"imp_country\":[\"write\"]}}");
        int st = mvc.perform(get("/api/export/dictionaries/imp_country").header(HttpHeaders.AUTHORIZATION, "Bearer " + w))
                .andReturn().getResponse().getStatus();
        System.out.println("PROBE e06 writeOnly status " + st);
        assertThat(st).isEqualTo(403);
        MvcResult unknown = mvc.perform(get("/api/export/dictionaries/nope").header(HttpHeaders.AUTHORIZATION, "Bearer "
                + token("{\"tables\":{\"*\":[\"read\"]}}"))).andReturn();
        System.out.println("PROBE e06 unknown " + unknown.getResponse().getStatus() + " " + unknown.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void e07_postExportBadBodies() throws Exception {
        String t = token("{\"tables\":{}}");
        MvcResult nullCol = postExport(t, "xlsx", "{\"title\":\"T\",\"columns\":[null],\"rows\":[]}");
        System.out.println("PROBE e07 nullColumn " + nullCol.getResponse().getStatus() + " " + nullCol.getResponse().getContentAsString(StandardCharsets.UTF_8));
        MvcResult badType = postExport(t, "xlsx", "{\"columns\":[{\"key\":\"a\",\"type\":\"money\"}],\"rows\":[]}");
        System.out.println("PROBE e07 badType " + badType.getResponse().getStatus() + " " + badType.getResponse().getContentAsString(StandardCharsets.UTF_8));
        MvcResult nullRow = postExport(t, "csv", "{\"columns\":[{\"key\":\"a\"}],\"rows\":[null,{\"a\":null},{\"a\":{\"x\":1}},{\"a\":[1,2]}]}");
        System.out.println("PROBE e07 nullRow " + nullRow.getResponse().getStatus() + " " + nullRow.getResponse().getContentAsString(StandardCharsets.UTF_8));
        MvcResult numStr = postExport(t, "xlsx", "{\"columns\":[{\"key\":\"a\",\"type\":\"NUMBER\"},{\"key\":\"d\",\"type\":\"DATE\"}],"
                + "\"rows\":[{\"a\":\"abc\",\"d\":\"не дата\"},{\"a\":12345678901234567.89,\"d\":\"1850-01-01\"}]}");
        System.out.println("PROBE e07 numStr " + numStr.getResponse().getStatus());
        if (numStr.getResponse().getStatus() == 200) {
            try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(numStr.getResponse().getContentAsByteArray()))) {
                System.out.println("PROBE e07 bigNumber cell=" + book.getSheetAt(0).getRow(2).getCell(0)
                        + " date1850=" + book.getSheetAt(0).getRow(2).getCell(1));
            }
        }
        MvcResult tooMany = postExport(t, "csv", "{\"columns\":[{\"key\":\"a\"}],\"rows\":[" + "{},".repeat(MAX_ROWS) + "{}]}");
        MvcResult anon = mvc.perform(post("/api/export/csv").contentType(MediaType.APPLICATION_JSON).content("{\"columns\":[{\"key\":\"a\"}]}")).andReturn();
        assertThat(tooMany.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Слишком много строк для экспорта: больше " + MAX_ROWS);
        assertThat(anon.getResponse().getStatus()).isEqualTo(401);
        assertThat(nullRow.getResponse().getStatus()).isEqualTo(200);
        assertThat(badType.getResponse().getStatus()).isEqualTo(400);
        assertThat(nullCol.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void e08_csvFormulaVariants() {
        TableExporter exporter = new TableExporter();
        TableData data = new TableData("T", List.of(new TableData.Column("a", "A", null)),
                List.of(Map.of("a", " =1+1"), Map.of("a", "\n=1+1"), Map.of("a", "\uFF1D1+1"), Map.of("a", "|cmd")));
        String csv = new String(exporter.toCsv(data), StandardCharsets.UTF_8);
        System.out.println("PROBE e08 " + csv.replace("\r", "\\r").replace("\n", "\\n"));
    }

    @Test
    void e09_dateBefore1900RoundTrip() throws Exception {
        jdbc.update("insert into imp_country(name, founded) values ('Old','1850-03-04')");
        byte[] x = mvc.perform(get("/api/export/dictionaries/imp_country").header(HttpHeaders.AUTHORIZATION, "Bearer " + writer))
                .andReturn().getResponse().getContentAsByteArray();
        upload(writer, "imp_country", x, "c.xlsx", "mode", "UPSERT");
        assertThat(jdbc.queryForObject("select founded from imp_country", LocalDate.class)).isEqualTo(LocalDate.of(1850, 3, 4));
    }

    // ---------------- helpers ----------------

    String exportBody(String format, String search, String sort) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/export/dictionaries/imp_country").param("format", format)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer);
        if (search != null) req.param("search", search);
        if (sort != null) req.param("sort", sort);
        return mvc.perform(req).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    MvcResult postExport(String token, String format, String json) throws Exception {
        return mvc.perform(post("/api/export/" + format).contentType(MediaType.APPLICATION_JSON).content(json)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    String workbook(String token, byte[] file, String mode) throws Exception {
        MvcResult r = mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "b.xlsx", XLSX, file))
                .param("mode", mode).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        System.out.println("PROBE workbook " + mode + " -> " + r.getResponse().getStatus() + " " + body);
        return body;
    }

    String upload(String token, String code, byte[] file, String name, String... params) throws Exception {
        MvcResult r = uploadResult(token, code, file, name, params);
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        System.out.println("PROBE upload " + code + " " + name + " " + Arrays.toString(params) + " -> "
                + r.getResponse().getStatus() + " " + (body.length() > 700 ? body.substring(0, 700) : body));
        return body;
    }

    MvcResult uploadResult(String token, String code, byte[] file, String name, String... params) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart(BASE + "/" + code)
                .file(new MockMultipartFile("file", name, name.endsWith(".csv") ? "text/csv" : XLSX, file));
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        return mvc.perform(req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    @SafeVarargs
    static SheetData sheet(List<String> headers, Map<String, String>... rows) {
        List<RowData> list = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) list.add(new RowData(i + 2, rows[i]));
        return new SheetData("S", headers, list);
    }

    static List<List<Object>> rows(List<String> header, List<?>... rows) {
        List<List<Object>> result = new ArrayList<>();
        result.add(new ArrayList<>(header));
        for (List<?> row : rows) result.add(new ArrayList<>(row));
        return result;
    }

    static byte[] bytes(Workbook book) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        book.write(out);
        return out.toByteArray();
    }

    static byte[] xlsx(List<List<Object>> rows) throws Exception {
        Map<String, List<List<Object>>> m = new LinkedHashMap<>();
        m.put("S", rows);
        return xlsx(m);
    }

    static byte[] xlsx(Map<String, List<List<Object>>> sheets) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            CellStyle dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("dd.mm.yyyy"));
            sheets.forEach((name, rows) -> {
                Sheet sheet = book.createSheet(name);
                for (int r = 0; r < rows.size(); r++) {
                    Row row = sheet.createRow(r);
                    for (int c = 0; c < rows.get(r).size(); c++) {
                        Object v = rows.get(r).get(c);
                        if (v == null) continue;
                        var cell = row.createCell(c);
                        switch (v) {
                            case Number n -> cell.setCellValue(n.doubleValue());
                            case Boolean b -> cell.setCellValue(b);
                            case LocalDate d -> {
                                cell.setCellValue(d);
                                cell.setCellStyle(dateStyle);
                            }
                            default -> cell.setCellValue(v.toString());
                        }
                    }
                }
            });
            return bytes(book);
        }
    }

    String token(String grants) {
        User user = new User();
        user.setLogin("qxt-" + UUID.randomUUID());
        user.setDisplayName("QA transfer");
        user = userRepository.save(user);
        users.add(user.getId());
        Role role = new Role();
        role.setCode("QXT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("QA transfer");
        role.setGrants(grants);
        role = roleRepository.save(role);
        roles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return tokenService.issue(user);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CommonDictionary.class, User.class, Role.class, ImpCountry.class, AuditEntry.class})
    @EnableJpaRepositories(basePackageClasses = {CommonDictionaryRepository.class, UserRepository.class,
            RoleRepository.class, AuditEntryRepository.class})
    static class TestApplication {
    }
}
