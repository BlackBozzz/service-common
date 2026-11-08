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
import com.argent.servicecommon.transfer.exp.DictionaryExportService;
import com.argent.servicecommon.transfer.exp.TableData;
import com.argent.servicecommon.transfer.exp.TableExporter;
import com.argent.servicecommon.transfer.impfixture.ImpCountry;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Экспорт справочника и произвольной таблицы через REST: заголовки по подписям DTO, ссылки
 * подписью, права чтения, отказ для PDF и CSV с BOM.
 */
@SpringBootTest(
        classes = DictionaryExportIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class DictionaryExportIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String READER = "{\"tables\":{\"imp_country\":[\"read\"]}}";
    private static final String NOBODY = "{\"tables\":{\"imp_region\":[\"read\"]}}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_export");
        registry.add("argent.transfer.export-max-columns", () -> "10");
        registry.add("argent.transfer.export-max-cells", () -> "40");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DictionaryExportService exportService;
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
        jdbc.update("insert into imp_region(name) values ('Северный')");
        Long region = jdbc.queryForObject("select id from imp_region where name = 'Северный'", Long.class);
        jdbc.update("insert into imp_country(name, code, population, founded, active, region_id) "
                + "values ('Франция', 'FR', 68000000, ?, true, ?)", Date.valueOf(LocalDate.of(1958, 10, 4)), region);
        jdbc.update("insert into imp_country(name, code, population, active) values ('Чили', '=1+1', 19000000, false)");
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
    void shouldExportDictionaryToXlsx() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/export/dictionaries/imp_country")
                        .param("format", "xlsx").param("sort", "name,asc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(READER)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, XLSX))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;")
                .contains("filename*=UTF-8''").endsWith(".xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            Sheet sheet = book.getSheetAt(0);
            List<String> headers = new ArrayList<>();
            sheet.getRow(0).forEach(cell -> headers.add(cell.getStringCellValue()));
            assertThat(headers).containsExactly("Идентификатор", "Название", "Код", "Население", "Площадь",
                    "Дата основания", "Активна", "Регион");
            Row france = sheet.getRow(1);
            assertThat(france.getCell(1).getStringCellValue()).isEqualTo("Франция");
            assertThat(france.getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(france.getCell(3).getNumericCellValue()).isEqualTo(68_000_000d);
            assertThat(DateUtil.isCellDateFormatted(france.getCell(5))).isTrue();
            assertThat(france.getCell(6).getStringCellValue()).isEqualTo("Да");
            assertThat(france.getCell(7).getStringCellValue()).isEqualTo("Северный");
            Row chile = sheet.getRow(2);
            assertThat(chile.getCell(1).getStringCellValue()).isEqualTo("Чили");
            assertThat(chile.getCell(6).getStringCellValue()).isEqualTo("Нет");
            assertThat(chile.getCell(7) == null || chile.getCell(7).getCellType() == CellType.BLANK).isTrue();
        }
    }

    @Test
    void shouldBuildTableDataWithDictionaryTitle() {
        TableData data = exportService.build("imp_country", "Фран", null);

        assertThat(data.title()).isEqualTo("Страны (импорт)");
        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().getFirst().get("region")).isEqualTo("Северный");
        assertThat(data.columns().stream().map(TableData.Column::type).toList()).containsExactly(
                TableData.ColumnType.NUMBER, TableData.ColumnType.TEXT, TableData.ColumnType.TEXT,
                TableData.ColumnType.NUMBER, TableData.ColumnType.NUMBER, TableData.ColumnType.DATE,
                TableData.ColumnType.BOOLEAN, TableData.ColumnType.TEXT);
    }

    @Test
    void shouldEscapeDictionaryCsv() throws Exception {
        byte[] body = mvc.perform(get("/api/export/dictionaries/imp_country")
                        .param("format", "csv").param("search", "Чили")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(READER)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andReturn().getResponse().getContentAsByteArray();

        String text = new String(body, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Идентификатор;Название;");
        assertThat(text).contains(";Чили;'=1+1;19000000;");
    }

    @Test
    void shouldRequireReadPermission() throws Exception {
        mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "xlsx")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(NOBODY)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "xlsx"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectPdfAndUnknownFormat() throws Exception {
        String token = tokenWith(READER);
        mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Формат PDF не поддерживается"));
        mvc.perform(post("/api/export/odt").contentType(MediaType.APPLICATION_JSON).content(tableJson())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Неизвестный формат экспорта: odt"));
        mvc.perform(post("/api/export/pdf").contentType(MediaType.APPLICATION_JSON).content(tableJson())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Формат PDF не поддерживается"));
    }

    @Test
    void shouldExportPostedTableToCsv() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/export/csv")
                        .contentType(MediaType.APPLICATION_JSON).content(tableJson())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(NOBODY)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse();

        byte[] body = response.getContentAsByteArray();
        assertThat(Arrays.copyOf(body, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("Товар;Цена;Поставка\r\n'=HYPERLINK();12,5;01.02.2026\r\n");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;")
                .contains("filename*=UTF-8''%D0%9F%D1%80%D0%B0%D0%B9%D1%81.csv");

        mvc.perform(post("/api/export/csv").contentType(MediaType.APPLICATION_JSON).content(tableJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectPostedTableOverColumnAndCellLimits() throws Exception {
        String token = tokenWith(NOBODY);
        StringBuilder wide = new StringBuilder("{\"title\":\"Т\",\"columns\":[");
        for (int i = 0; i < 11; i++) {
            wide.append(i == 0 ? "" : ",").append("{\"key\":\"c").append(i).append("\",\"label\":\"К\"}");
        }
        wide.append("],\"rows\":[]}");
        mvc.perform(post("/api/export/xlsx").contentType(MediaType.APPLICATION_JSON).content(wide.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Слишком много колонок: 11, допустимо не более 10"));

        StringBuilder tall = new StringBuilder("{\"title\":\"Т\",\"columns\":[{\"key\":\"a\",\"label\":\"A\"},"
                + "{\"key\":\"b\",\"label\":\"B\"},{\"key\":\"c\",\"label\":\"C\"}],\"rows\":[");
        for (int i = 0; i < 14; i++) {
            tall.append(i == 0 ? "" : ",").append("{\"a\":\"x\"}");
        }
        tall.append("]}");
        mvc.perform(post("/api/export/csv").contentType(MediaType.APPLICATION_JSON).content(tall.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Слишком много ячеек: 42, допустимо не более 40"));
    }

    @Test
    void shouldRejectDictionaryExportOverCellLimit() throws Exception {
        for (int i = 0; i < 6; i++) {
            jdbc.update("insert into imp_country(name, code) values (?, ?)", "Страна " + i, "C" + i);
        }
        int columns = exportService.build("imp_country", null, null).columns().size();
        mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "xlsx")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(READER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Слишком много ячеек: " + 8 * columns
                        + ", допустимо не более 40"));
    }

    @Test
    void exportsMoreThanOnePage() {
        // Лимит ячеек класса (40) мал для 150 строк: страницы читает сервис, XLSX собирает экспортёр по умолчанию.
        for (int i = 0; i < 148; i++) {
            jdbc.update("insert into imp_country(name, code) values (?, ?)", "Страна " + i, "C" + i);
        }

        TableData data = exportService.build("imp_country", null, "name,asc");
        byte[] xlsx = new TableExporter().toXlsx(data);

        assertThat(data.rows()).hasSize(150);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(150);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static String tableJson() {
        return """
                {"title":"Прайс","columns":[{"key":"name","label":"Товар","type":"TEXT"},
                {"key":"price","label":"Цена","type":"NUMBER"},{"key":"date","label":"Поставка","type":"DATE"}],
                "rows":[{"name":"=HYPERLINK()","price":12.5,"date":"2026-02-01"}]}
                """;
    }

    private String tokenWith(String grants) {
        User user = new User();
        user.setLogin("export-" + UUID.randomUUID());
        user.setDisplayName("Пользователь экспорта");
        user = userRepository.save(user);
        createdUsers.add(user.getId());

        Role role = new Role();
        role.setCode("EXPORT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Роль экспорта");
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
