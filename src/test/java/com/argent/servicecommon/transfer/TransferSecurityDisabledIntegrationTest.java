package com.argent.servicecommon.transfer;

import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.transfer.impfixture.ImpCountry;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Импорт и экспорт при {@code argent.security.enabled=false}: без токена, листы книги не пишутся. */
@SpringBootTest(
        classes = TransferSecurityDisabledIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class TransferSecurityDisabledIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_import_nosec");
        registry.add("argent.security.enabled", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("delete from imp_country");
    }

    @Test
    void singleImportWorks() throws Exception {
        mvc.perform(multipart("/api/import/dictionaries/imp_country")
                        .file(new MockMultipartFile("file", "c.xlsx", XLSX, book("imp_country", "Франция")))
                        .param("mode", "REPLACE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        assertThat(jdbc.queryForList("select name from imp_country", String.class)).containsExactly("Франция");
    }

    @Test
    void workbookImportRejectsEverySheet() throws Exception {
        mvc.perform(multipart("/api/import/dictionaries")
                        .file(new MockMultipartFile("file", "b.xlsx", XLSX, book("imp_country", "Франция")))
                        .param("mode", "UPSERT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].report.errors[0].message").value("Нет права записи в справочник"));

        assertThat(jdbc.queryForObject("select count(*) from imp_country", Integer.class)).isZero();
    }

    @Test
    void exportWorks() throws Exception {
        jdbc.update("insert into imp_country(name, code) values ('Франция', 'FR')");

        byte[] body = mvc.perform(get("/api/export/dictionaries/imp_country").param("format", "xlsx"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
        }
    }

    private static byte[] book(String sheetName, String name) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(sheetName);
            sheet.createRow(0).createCell(0).setCellValue("Название");
            sheet.createRow(1).createCell(0).setCellValue(name);
            book.write(out);
            return out.toByteArray();
        }
    }

    /** Тестовая конфигурация с фикстурами-справочниками импорта. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
    })
    @EntityScan(basePackageClasses = {CommonDictionary.class, ImpCountry.class})
    @EnableJpaRepositories(basePackageClasses = CommonDictionaryRepository.class)
    static class TestApplication {
    }
}
