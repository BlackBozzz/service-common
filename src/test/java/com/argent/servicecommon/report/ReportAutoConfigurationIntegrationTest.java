package com.argent.servicecommon.report;

import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Автоконфигурация отчётов: бины, предкомпиляция, ответ 400 на ошибку параметров и согласование содержимого JSON. */
@SpringBootTest(classes = {SecurityTestApplication.class, ReportAutoConfigurationIntegrationTest.StubConfiguration.class})
@AutoConfigureMockMvc
class ReportAutoConfigurationIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_report");
        registry.add("argent.reports.preload", () -> "classpath*:reports/hello.jrxml");
        registry.add("argent.security.permit-paths", () -> "/api/test-report/**");
    }

    @Autowired private ReportService service;
    @Autowired private ReportTemplateCache templates;
    @Autowired private MockMvc mvc;

    @Test
    void exposesReportServiceAndPreloads() {
        assertThat(service).isNotNull();
        // Шаблон из argent.reports.preload уже в кэше до первого get; соседний шаблон не тронут.
        assertThat(templates.isCached("classpath:reports/hello.jrxml")).isTrue();
        assertThat(templates.isCached("classpath:reports/with-items.jrxml")).isFalse();
    }

    @Test
    void jsonEndpointsStillAnswerJsonForBrowserAccept() throws Exception {
        mvc.perform(get("/api/auth/config")
                        .accept("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/json")));
    }

    @Test
    void parameterExceptionMapsToBadRequest() throws Exception {
        mvc.perform(get("/api/test-report/bad-parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("тест"));
    }

    /** Контроллер-заглушка, бросающий ошибку параметров отчёта. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        BadParameterController badParameterController() {
            return new BadParameterController();
        }
    }

    @RestController
    public static class BadParameterController {

        @GetMapping("/api/test-report/bad-parameter")
        public String badParameter() {
            throw new ReportParameterException("тест");
        }
    }
}
