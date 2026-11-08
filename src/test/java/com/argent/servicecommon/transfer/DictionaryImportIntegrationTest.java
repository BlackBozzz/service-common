package com.argent.servicecommon.transfer;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.argent.servicecommon.transfer.imp.DictionaryImportService;
import com.argent.servicecommon.transfer.imp.ImportError;
import com.argent.servicecommon.transfer.imp.ImportFailedException;
import com.argent.servicecommon.transfer.imp.ImportMode;
import com.argent.servicecommon.transfer.imp.ImportReport;
import com.argent.servicecommon.transfer.imp.RowData;
import com.argent.servicecommon.transfer.imp.SheetData;
import com.argent.servicecommon.transfer.impfixture.ImpCountry;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Импорт листов XLSX/CSV в справочники через REST: REPLACE, UPSERT, преобразование значений,
 * ссылки по подписи, отчёт об ошибках с откатом, многолистовой импорт и права доступа.
 */
@SpringBootTest(
        classes = DictionaryImportIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class DictionaryImportIntegrationTest {

    private static final String BASE = "/api/import/dictionaries";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_UPLOAD_BYTES = 200_000;

    private static final String WRITER =
            "{\"tables\":{\"imp_country\":[\"read\",\"write\"],\"imp_region\":[\"read\"]},\"actions\":[\"import\"]}";
    private static final String WRITE_WITHOUT_IMPORT = "{\"tables\":{\"imp_country\":[\"read\",\"write\"]}}";
    private static final String IMPORT_WITHOUT_WRITE =
            "{\"tables\":{\"imp_country\":[\"read\"]},\"actions\":[\"import\"]}";
    private static final String ALL_WRITER = "{\"tables\":{\"imp_country\":[\"read\",\"write\"],"
            + "\"imp_region\":[\"read\",\"write\"],\"imp_with_collection\":[\"read\",\"write\"]},"
            + "\"actions\":[\"import\"]}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_import");
        registry.add("argent.transfer.max-upload-bytes", () -> String.valueOf(MAX_UPLOAD_BYTES));
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DictionaryImportService importService;
    @Autowired private TokenService tokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    @BeforeEach
    void seed() {
        jdbc.update("delete from imp_country");
        jdbc.update("delete from imp_region");
        jdbc.update("delete from imp_with_collection_tag");
        jdbc.update("delete from imp_with_collection");
        jdbc.update("insert into imp_region(name) values ('Центральный'), ('Северный')");
    }

    @AfterEach
    void cleanUp() {
        for (UUID userId : createdUsers) {
            userRoleRepository.deleteAll(userRoleRepository.findByIdUserId(userId));
            userSessionRepository.deleteAll(userSessionRepository.findAll().stream()
                    .filter(session -> session.getUserId().equals(userId))
                    .toList());
            userRepository.deleteById(userId);
        }
        createdRoles.forEach(roleRepository::deleteById);
        createdUsers.clear();
        createdRoles.clear();
    }

    @Test
    void shouldImportReplace() throws Exception {
        insertCountry("Старая 1", 1, null);
        insertCountry("Старая 2", 2, null);
        byte[] file = xlsx("Страны", table(
                List.of("Название", "Код", "Население"),
                Arrays.asList("Франция", "FR", 68_000_000),
                Arrays.asList("Чили", "CL", 19_000_000)));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors", empty()));

        assertThat(jdbc.queryForList("select name from imp_country order by name", String.class))
                .containsExactly("Франция", "Чили");
        assertThat(population("Франция")).isEqualTo(68_000_000);
    }

    @Test
    void shouldImportUpsertByKey() throws Exception {
        insertCountry("Франция", 100, LocalDate.of(1789, 7, 14));
        byte[] file = xlsx("Страны", table(
                List.of("Название", "Население", "Дата основания"),
                Arrays.asList("Франция", 68_000_000, null),
                Arrays.asList("Чили", 19_000_000, "18.09.1810")));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "UPSERT", "keyFields", "name")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        assertThat(population("Франция")).isEqualTo(68_000_000);
        assertThat(founded("Франция")).isEqualTo(LocalDate.of(1789, 7, 14));
        assertThat(jdbc.queryForObject("select code from imp_country where name = 'Франция'", String.class))
                .isEqualTo("FR");
        assertThat(founded("Чили")).isEqualTo(LocalDate.of(1810, 9, 18));
    }

    @Test
    void shouldDeriveKeyFieldsFromUniqueConstraint() throws Exception {
        insertCountry("Франция", 100, null);
        byte[] file = xlsx("Страны", table(List.of("Название", "Население"), Arrays.asList("Франция", 200)));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "UPSERT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1));

        assertThat(jdbc.queryForObject("select count(*) from imp_country", Integer.class)).isEqualTo(1);
        assertThat(population("Франция")).isEqualTo(200);
    }

    @Test
    void shouldConvertRussianDateAndNumber() throws Exception {
        byte[] file = xlsx("Страны", table(
                List.of("Название", "Площадь", "Дата основания", "Активна"),
                Arrays.asList("Франция", "1 234,50", "31.12.2025", "да"),
                Arrays.asList("Чили", 1234.5, LocalDate.of(2025, 12, 31), true)));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));

        for (String name : List.of("Франция", "Чили")) {
            assertThat(jdbc.queryForObject("select area from imp_country where name = ?", BigDecimal.class, name))
                    .isEqualByComparingTo("1234.50");
            assertThat(founded(name)).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(jdbc.queryForObject("select active from imp_country where name = ?", Boolean.class, name))
                    .isTrue();
        }
    }

    @Test
    void shouldResolveReferenceByLabel() throws Exception {
        byte[] file = xlsx("Страны", table(List.of("Название", "Регион"), Arrays.asList("Франция", "северный")));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        assertThat(jdbc.queryForObject("select region_id from imp_country where name = 'Франция'", Long.class))
                .isEqualTo(regionId("Северный"));

        byte[] unknown = xlsx("Страны", table(List.of("Название", "Регион"), Arrays.asList("Чили", "Южный")));
        upload(tokenWith(WRITER), "imp_country", unknown, "countries.xlsx", "mode", "UPSERT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.report.errors[0].row").value(2))
                .andExpect(jsonPath("$.report.errors[0].field").value("region"))
                .andExpect(jsonPath("$.report.errors[0].message")
                        .value("Не найдена запись справочника imp_region: Южный"));
    }

    @Test
    void shouldUpsertByCompositeKeyWithReference() throws Exception {
        jdbc.update("insert into imp_country(name, population, region_id) values ('Франция', 1, ?)",
                regionId("Центральный"));
        byte[] file = xlsx("Страны", table(
                List.of("Название", "Регион", "Население"),
                Arrays.asList("Франция", "Центральный", 5)));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx",
                "mode", "UPSERT", "keyFields", "name, region")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.created").value(0));

        assertThat(population("Франция")).isEqualTo(5);
    }

    @Test
    void shouldApplyExplicitMapping() throws Exception {
        byte[] file = xlsx("Страны", table(
                List.of("Страна", "Жители", "Комментарий"),
                Arrays.asList("Франция", 7, "не импортируется")));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "REPLACE",
                "mapping", "{\"Страна\":\"name\",\"Жители\":\"population\",\"Комментарий\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors", empty()));

        assertThat(population("Франция")).isEqualTo(7);
    }

    @Test
    void shouldImportCsv() throws Exception {
        ByteArrayOutputStream csv = new ByteArrayOutputStream();
        csv.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        csv.write("Название;Площадь\r\nФранция;\"1 234,50\"\r\n".getBytes(StandardCharsets.UTF_8));

        upload(tokenWith(WRITER), "imp_country", csv.toByteArray(), "countries.csv", "mode", "REPLACE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        assertThat(jdbc.queryForObject("select area from imp_country where name = 'Франция'", BigDecimal.class))
                .isEqualByComparingTo("1234.50");
    }

    @Test
    void shouldReportRowErrorsAndRollback() throws Exception {
        insertCountry("Старая", 1, null);
        byte[] file = xlsx("Страны", table(
                List.of("Название", "Население"),
                Arrays.asList("Франция", 1),
                Arrays.asList("Чили", "много"),
                Arrays.asList("Перу", 3)));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Ошибка импорта"))
                .andExpect(jsonPath("$.report.total").value(3))
                .andExpect(jsonPath("$.report.created").value(0))
                .andExpect(jsonPath("$.report.updated").value(0))
                .andExpect(jsonPath("$.report.failed").value(1))
                .andExpect(jsonPath("$.report.errors[0].row").value(3))
                .andExpect(jsonPath("$.report.errors[0].field").value("population"))
                .andExpect(jsonPath("$.report.errors[0].message").value("Некорректное число: много"));

        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Старая");
    }

    @Test
    void shouldReportUnmappedColumn() throws Exception {
        insertCountry("Старая", 1, null);
        byte[] file = xlsx("Страны", table(List.of("Название", "Столица"), Arrays.asList("Франция", "Париж")));

        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.report.errors[0].row").value(0))
                .andExpect(jsonPath("$.report.errors[0].message")
                        .value("Колонка не сопоставлена с полем: Столица"));

        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Старая");
    }

    @Test
    void shouldKeepRowsWhenReplaceSheetHasUnmappedColumn() {
        insertCountry("Старая", 1, null);
        SheetData sheet = new SheetData("Страны", List.of("Название", "Столица"), List.of(
                new RowData(2, Map.of("Название", "Франция", "Столица", "Париж"))));

        assertThatThrownBy(() -> importService.importSheet("imp_country", sheet, ImportMode.REPLACE,
                null, TransferProperties.ImportOnError.SKIP_ROW))
                .isInstanceOfSatisfying(ImportFailedException.class, exception -> {
                    assertThat(exception.report().created()).isZero();
                    assertThat(exception.report().errors()).extracting(error -> error.row() + ":" + error.message())
                            .containsExactly("0:Колонка не сопоставлена с полем: Столица",
                                    "0:" + DictionaryImportService.REPLACE_SKIPPED);
                });

        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Старая");
    }

    @Test
    void shouldKeepRowsWhenReplaceHasNoValidRows() {
        insertCountry("Старая", 1, null);
        SheetData sheet = new SheetData("Страны", List.of("Название", "Население"), List.of(
                new RowData(2, Map.of("Название", "Франция", "Население", "много")),
                new RowData(3, Map.of("Название", "Чили", "Население", "-5"))));

        assertThatThrownBy(() -> importService.importSheet("imp_country", sheet, ImportMode.REPLACE,
                null, TransferProperties.ImportOnError.SKIP_ROW))
                .isInstanceOfSatisfying(ImportFailedException.class, exception -> {
                    assertThat(exception.report().failed()).isEqualTo(2);
                    assertThat(exception.report().errors()).extracting(error -> error.row() + ":" + error.field())
                            .containsExactly("2:population", "3:population", "0:null");
                });

        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Старая");
    }

    @Test
    void shouldKeepRowsWhenReplaceSheetIsEmpty() throws Exception {
        insertCountry("Старая", 1, null);
        SheetData headerOnly = new SheetData("Страны", List.of("Название"), List.of());

        for (TransferProperties.ImportOnError onError : TransferProperties.ImportOnError.values()) {
            assertThatThrownBy(() -> importService.importSheet("imp_country", headerOnly, ImportMode.REPLACE,
                    null, onError))
                    .isInstanceOfSatisfying(ImportFailedException.class, exception -> assertThat(
                            exception.report().errors()).extracting(error -> error.row() + ":" + error.message())
                            .containsExactly("0:Лист не содержит данных"));
        }
        byte[] file = xlsx("Страны", table(List.of("Название")));
        upload(tokenWith(WRITER), "imp_country", file, "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.report.errors[0].row").value(0))
                .andExpect(jsonPath("$.report.errors[0].message").value("Лист не содержит данных"));

        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Старая");
    }

    @Test
    void shouldKeepDictionaryWhenWorkbookSheetIsBlank() throws Exception {
        insertCountry("Старая", 1, null);
        Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
        sheets.put("imp_country", List.of());
        sheets.put("Страны (импорт)", table(List.of("Название")));

        uploadWorkbook(tokenWith(ALL_WRITER), xlsx(sheets), "mode", "REPLACE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].dictionary").value("imp_country"))
                .andExpect(jsonPath("$[0].report.errors[0].message").value("Лист не содержит данных"))
                .andExpect(jsonPath("$[1].dictionary").value("imp_country"))
                .andExpect(jsonPath("$[1].report.errors[0].message").value("Лист не содержит данных"));

        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Старая");
    }

    @Test
    void shouldReplaceWithValidRowsOnlyWhenSkipping() {
        insertCountry("Старая", 1, null);
        SheetData sheet = new SheetData("Страны", List.of("Название", "Население"), List.of(
                new RowData(2, Map.of("Название", "Франция", "Население", "много")),
                new RowData(3, Map.of("Название", "Чили", "Население", "19"))));

        ImportReport report = importService.importSheet("imp_country", sheet, ImportMode.REPLACE,
                null, TransferProperties.ImportOnError.SKIP_ROW);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.errors()).extracting(ImportError::row).containsExactly(2);
        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Чили");
    }

    @Test
    void shouldRejectInvalidMapping() throws Exception {
        byte[] file = xlsx("Страны", table(List.of("Название"), Arrays.asList("Франция")));
        String token = tokenWith(WRITER);

        for (String mapping : List.of("null", "{bad", "[\"name\"]", "{\"Название\": 5}")) {
            upload(token, "imp_country", file, "countries.xlsx", "mode", "REPLACE", "mapping", mapping)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("mapping"))
                    .andExpect(jsonPath("$.errors[0].message").value("Некорректное сопоставление колонок"));
        }
    }

    @Test
    void shouldSkipFailedRowsWhenConfigured() {
        SheetData sheet = new SheetData("Страны", List.of("Название", "Население"), List.of(
                new RowData(2, Map.of("Название", "Франция", "Население", "1")),
                new RowData(3, Map.of("Название", "Чили", "Население", "-5")),
                new RowData(4, Map.of("Название", "", "Население", "7"))));

        ImportReport report = importService.importSheet("imp_country", sheet, ImportMode.UPSERT,
                null, TransferProperties.ImportOnError.SKIP_ROW);

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(2);
        assertThat(report.errors()).extracting(error -> error.row() + ":" + error.field())
                .containsExactly("3:population", "4:name");
        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Франция");
    }

    @Test
    void shouldCancelSheetOnDatabaseError() {
        insertCountry("Старая", 1, null);
        SheetData sheet = new SheetData("Страны", List.of("Название"), List.of(
                new RowData(2, Map.of("Название", "Франция")),
                new RowData(3, Map.of("Название", "Франция")),
                new RowData(4, Map.of("Название", "Чили"))));

        assertThatThrownBy(() -> importService.importSheet("imp_country", sheet, ImportMode.REPLACE,
                null, TransferProperties.ImportOnError.SKIP_ROW))
                .isInstanceOfSatisfying(ImportFailedException.class, exception -> {
                    assertThat(exception.report().created()).isZero();
                    assertThat(exception.report().failed()).isEqualTo(1);
                    assertThat(exception.report().errors()).singleElement().satisfies(error -> {
                        assertThat(error.row()).isEqualTo(3);
                        assertThat(error.message()).isEqualTo(
                                "Запись нарушает ограничение целостности базы данных; импорт листа отменён");
                    });
                });

        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Старая");
    }

    @Test
    void shouldRejectReplaceForCollections() throws Exception {
        byte[] file = xlsx("Метки", table(List.of("Название"), Arrays.asList("x")));

        upload(tokenWith(ALL_WRITER), "imp_with_collection", file, "tags.xlsx", "mode", "REPLACE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("REPLACE недоступен для справочника с коллекциями, используйте UPSERT"));
    }

    @Test
    void shouldRequireKeyFieldsForUpsert() throws Exception {
        byte[] file = xlsx("Метки", table(List.of("Название"), Arrays.asList("x")));

        upload(tokenWith(ALL_WRITER), "imp_with_collection", file, "tags.xlsx", "mode", "UPSERT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Укажите ключевые поля для режима UPSERT"));
    }

    @Test
    void shouldRejectReplaceOfReferencedRecords() throws Exception {
        jdbc.update("insert into imp_country(name, region_id) values ('Франция', ?)", regionId("Центральный"));
        byte[] file = xlsx("Регионы", table(List.of("Название"), Arrays.asList("Южный")));

        upload(tokenWith(ALL_WRITER), "imp_region", file, "regions.xlsx", "mode", "REPLACE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("На записи справочника ссылаются другие данные"));

        assertThat(jdbc.queryForList("select name from imp_region order by name", String.class))
                .containsExactly("Северный", "Центральный");
    }

    @Test
    void shouldReportMissingSheetAndLargeFile() throws Exception {
        byte[] file = xlsx("Страны", table(List.of("Название"), Arrays.asList("Франция")));
        String token = tokenWith(WRITER);

        upload(token, "imp_country", file, "countries.xlsx", "mode", "REPLACE", "sheet", "Нет такого")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Лист не найден: Нет такого"));
        upload(token, "imp_country", new byte[MAX_UPLOAD_BYTES + 1], "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Файл превышает допустимый размер"));
    }

    @Test
    void shouldRequireImportActionAndWrite() throws Exception {
        byte[] file = xlsx("Страны", table(List.of("Название"), Arrays.asList("Франция")));

        upload(tokenWith(WRITE_WITHOUT_IMPORT), "imp_country", file, "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isForbidden());
        upload(tokenWith(IMPORT_WITHOUT_WRITE), "imp_country", file, "countries.xlsx", "mode", "REPLACE")
                .andExpect(status().isForbidden());
        uploadWorkbook(tokenWith(WRITE_WITHOUT_IMPORT), file, "mode", "UPSERT")
                .andExpect(status().isForbidden());
        mvc.perform(multipart(BASE + "/imp_country")
                        .file(new MockMultipartFile("file", "countries.xlsx", XLSX, file))
                        .param("mode", "REPLACE"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("select count(*) from imp_country", Integer.class)).isZero();
    }

    @Test
    void shouldImportWholeWorkbookPerSheet() throws Exception {
        Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
        sheets.put("Страны (импорт)", table(
                List.of("Название", "Население"),
                Arrays.asList("Франция", "много")));
        sheets.put("imp_region", table(List.of("Название"), Arrays.asList("Южный")));

        uploadWorkbook(tokenWith(ALL_WRITER), xlsx(sheets), "mode", "UPSERT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sheet").value("Страны (импорт)"))
                .andExpect(jsonPath("$[0].dictionary").value("imp_country"))
                .andExpect(jsonPath("$[0].report.created").value(0))
                .andExpect(jsonPath("$[0].report.failed").value(1))
                .andExpect(jsonPath("$[0].report.errors[0].row").value(2))
                .andExpect(jsonPath("$[0].report.errors[0].field").value("population"))
                .andExpect(jsonPath("$[1].sheet").value("imp_region"))
                .andExpect(jsonPath("$[1].dictionary").value("imp_region"))
                .andExpect(jsonPath("$[1].report.created").value(1))
                .andExpect(jsonPath("$[1].report.errors", empty()));

        assertThat(jdbc.queryForObject("select count(*) from imp_region where name = 'Южный'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from imp_country", Integer.class)).isZero();
    }

    @Test
    void shouldReportSheetsWithoutDictionaryOrWrite() throws Exception {
        Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
        sheets.put("imp_region", table(List.of("Название"), Arrays.asList("Южный")));
        sheets.put("Неизвестный", table(List.of("Название"), Arrays.asList("x")));

        uploadWorkbook(tokenWith(WRITER), xlsx(sheets), "mode", "UPSERT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dictionary").value("imp_region"))
                .andExpect(jsonPath("$[0].report.failed").value(1))
                .andExpect(jsonPath("$[0].report.errors[0].message").value("Нет права записи в справочник"))
                .andExpect(jsonPath("$[1].dictionary").doesNotExist())
                .andExpect(jsonPath("$[1].report.errors[0].message")
                        .value("Справочник для листа не найден: Неизвестный"));

        assertThat(jdbc.queryForObject("select count(*) from imp_region where name = 'Южный'", Integer.class))
                .isZero();
    }

    private ResultActions upload(String token, String code, byte[] file, String filename, String... params)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(BASE + "/" + code)
                .file(new MockMultipartFile("file", filename, filename.endsWith(".csv") ? "text/csv" : XLSX, file));
        return perform(request, token, params);
    }

    private ResultActions uploadWorkbook(String token, byte[] file, String... params) throws Exception {
        return perform(multipart(BASE).file(new MockMultipartFile("file", "book.xlsx", XLSX, file)), token, params);
    }

    private ResultActions perform(MockMultipartHttpServletRequestBuilder request, String token, String... params)
            throws Exception {
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static List<List<Object>> table(List<String> header, List<?>... rows) {
        List<List<Object>> result = new ArrayList<>();
        result.add(new ArrayList<>(header));
        for (List<?> row : rows) {
            result.add(new ArrayList<>(row));
        }
        return result;
    }

    private static byte[] xlsx(String sheetName, List<List<Object>> rows) throws IOException {
        Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
        sheets.put(sheetName, rows);
        return xlsx(sheets);
    }

    /** Строит книгу XLSX; первая строка каждого листа — заголовки, {@code null} — пустая ячейка. */
    private static byte[] xlsx(Map<String, List<List<Object>>> sheets) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("dd.mm.yyyy"));
            sheets.forEach((name, rows) -> {
                Sheet sheet = book.createSheet(name);
                for (int r = 0; r < rows.size(); r++) {
                    Row row = sheet.createRow(r);
                    List<Object> values = rows.get(r);
                    for (int c = 0; c < values.size(); c++) {
                        Object value = values.get(c);
                        if (value == null) continue;
                        var cell = row.createCell(c);
                        switch (value) {
                            case Number number -> cell.setCellValue(number.doubleValue());
                            case Boolean bool -> cell.setCellValue(bool);
                            case LocalDate date -> {
                                cell.setCellValue(date);
                                cell.setCellStyle(dateStyle);
                            }
                            default -> cell.setCellValue(value.toString());
                        }
                    }
                }
            });
            book.write(out);
            return out.toByteArray();
        }
    }

    private void insertCountry(String name, Integer population, LocalDate founded) {
        jdbc.update("insert into imp_country(name, code, population, founded) values (?, 'FR', ?, ?)",
                name, population, founded == null ? null : Date.valueOf(founded));
    }

    private Integer population(String name) {
        return jdbc.queryForObject("select population from imp_country where name = ?", Integer.class, name);
    }

    private LocalDate founded(String name) {
        return jdbc.queryForObject("select founded from imp_country where name = ?", LocalDate.class, name);
    }

    private Long regionId(String name) {
        return jdbc.queryForObject("select id from imp_region where name = ?", Long.class, name);
    }

    private String tokenWith(String grants) {
        User user = new User();
        user.setLogin("import-" + UUID.randomUUID());
        user.setDisplayName("Пользователь импорта");
        user = userRepository.save(user);
        createdUsers.add(user.getId());

        Role role = new Role();
        role.setCode("IMPORT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Роль импорта");
        role.setGrants(grants);
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return tokenService.issue(user);
    }

    /** Тестовая конфигурация с фикстурами-справочниками импорта. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CommonDictionary.class, User.class, Role.class, ImpCountry.class})
    @EnableJpaRepositories(basePackageClasses = {
            CommonDictionaryRepository.class, UserRepository.class, RoleRepository.class
    })
    static class TestApplication {
    }
}
