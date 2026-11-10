package com.argent.servicecommon.report.config;

import com.argent.servicecommon.config.ServiceCommonAutoConfiguration;
import com.argent.servicecommon.report.ReportJasperContext;
import com.argent.servicecommon.report.ReportProperties;
import com.argent.servicecommon.report.ReportService;
import com.argent.servicecommon.report.ReportTemplateCache;
import net.sf.jasperreports.engine.JasperReportsContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.util.List;

/**
 * Автоконфигурация печати отчётов JasperReports: контекст, кэш шаблонов, {@link ReportService}
 * и предкомпиляция шаблонов из {@code argent.reports.preload} после создания всех бинов.
 */
@AutoConfiguration(after = ServiceCommonAutoConfiguration.class)
@EnableConfigurationProperties(ReportProperties.class)
public class ReportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "argentJasperReportsContext")
    public JasperReportsContext argentJasperReportsContext(ReportProperties properties) {
        return ReportJasperContext.create(properties.defaultFont());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportTemplateCache reportTemplateCache(ResourceLoader resourceLoader,
                                                   JasperReportsContext argentJasperReportsContext,
                                                   ReportProperties properties) {
        return new ReportTemplateCache(resourceLoader, argentJasperReportsContext, properties.watchFiles());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportService reportService(ReportTemplateCache templates,
                                       JasperReportsContext argentJasperReportsContext,
                                       ObjectProvider<DataSource> dataSource,
                                       ReportProperties properties) {
        return new ReportService(templates, argentJasperReportsContext, dataSource, properties);
    }

    /** Компилирует шаблоны при старте: ошибка в шаблоне прерывает запуск. */
    @Bean
    @ConditionalOnMissingBean(name = "reportPreloader")
    public SmartInitializingSingleton reportPreloader(ReportService reportService) {
        return reportService::preload;
    }

    /**
     * Jasper тянет за собой Jackson XML, и Spring начинает отвечать XML на браузерный {@code Accept}
     * ({@code application/xhtml+xml}); XML-конвертеры Jackson 2 убираются, чтобы JSON-эндпоинты остались JSON.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class ReportWebConfiguration {

        @Bean
        WebMvcConfigurer reportJsonOnlyConverters() {
            return new WebMvcConfigurer() {
                @Override
                public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                    // Совпадение по имени класса: Jackson 2 XML (MappingJackson2XmlHttpMessageConverter)
                    // убирается, Jackson 3 JacksonXmlHttpMessageConverter приложение могло добавить намеренно.
                    converters.removeIf(converter -> converter.getClass().getSimpleName().contains("Jackson2Xml"));
                }
            };
        }
    }
}
