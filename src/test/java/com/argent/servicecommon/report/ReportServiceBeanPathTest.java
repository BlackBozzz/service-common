package com.argent.servicecommon.report;

import net.sf.jasperreports.engine.JasperReportsContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.argent.servicecommon.report.ReportTestFiles.zipEntryText;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Формирование отчётов из коллекции DTO и из одних параметров, экспорт в PDF/XLSX/DOCX без Spring. */
@ExtendWith(OutputCaptureExtension.class)
class ReportServiceBeanPathTest {

    private static final String HELLO = "classpath:reports/hello.jrxml";
    private static final String WITH_ITEMS = "classpath:reports/with-items.jrxml";

    record Row(String name, BigDecimal amount, Instant createdAt) {
    }

    record Item(String name, int qty) {
    }

    private final ReportService service = createService(List.of());

    private static ReportService createService(List<String> preload) {
        return createService(preload, 100_000);
    }

    @SuppressWarnings("unchecked")
    private static ReportService createService(List<String> preload, int maxRows) {
        JasperReportsContext context = ReportJasperContext.create("DejaVu Sans");
        ReportTemplateCache templates = new ReportTemplateCache(new DefaultResourceLoader(), context, false);
        ObjectProvider<DataSource> dataSource = mock(ObjectProvider.class);
        when(dataSource.getIfAvailable()).thenReturn(null);
        ReportProperties properties = new ReportProperties(preload, maxRows, Duration.ofSeconds(60),
                Locale.forLanguageTag("ru-RU"), "DejaVu Sans", true);
        return new ReportService(templates, context, dataSource, properties);
    }

    private static ReportRequest helloWithIvan() {
        return ReportRequest.template(HELLO)
                .data(List.of(new Row("Иван", new BigDecimal("10.50"), Instant.now())))
                .param("title", "Отчёт за ноябрь");
    }

    @Test
    void rendersPdfWithCyrillicFont() {
        ReportFile file = service.render(helloWithIvan().format(ReportFormat.PDF));
        assertThat(new String(file.content(), 0, 5, US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdfHasFont(file.content(), "DejaVu")).isTrue();
        assertThat(file.fileName()).isEqualTo("hello.pdf");
        assertThat(file.format()).isEqualTo(ReportFormat.PDF);
    }

    @Test
    void rendersXlsxWithRecordFields() throws IOException {
        ReportFile file = service.render(helloWithIvan().format(ReportFormat.XLSX).fileName("Заказы"));
        String strings = zipEntryText(file.content(), "xl/sharedStrings.xml");
        assertThat(strings).contains("Иван").contains("Отчёт за ноябрь");
        assertThat(file.fileName()).isEqualTo("Заказы.xlsx");
    }

    @Test
    void rendersDocx() throws IOException {
        ReportFile file = service.render(helloWithIvan().format(ReportFormat.DOCX));
        assertThat(zipEntryText(file.content(), "word/document.xml")).contains("Иван");
        assertThat(file.fileName()).isEqualTo("hello.docx");
    }

    @Test
    void shouldRenderTitleWhenDataIsEmpty() {
        ReportFile file = service.render(ReportRequest.template(HELLO)
                .data(List.of()).param("title", "Пусто").format(ReportFormat.PDF));
        assertThat(new String(file.content(), 0, 5, US_ASCII)).isEqualTo("%PDF-");
        assertThat(file.content().length).isGreaterThan(500);
    }

    @Test
    void shouldRenderRowsWithNullValues() {
        ReportFile file = service.render(ReportRequest.template(HELLO)
                .data(List.of(new Row("Пётр", null, null))).param("title", "С пустыми").format(ReportFormat.PDF));
        assertThat(new String(file.content(), 0, 5, US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void wrapsCollectionParameterAsDataSource() throws IOException {
        ReportFile file = service.render(ReportRequest.template(WITH_ITEMS)
                .param("heading", "Позиции")
                .param("items", List.of(new Item("Болт", 3), new Item("Гайка", 5)))
                .format(ReportFormat.XLSX));
        String strings = zipEntryText(file.content(), "xl/sharedStrings.xml");
        assertThat(strings).contains("Позиции").contains("Болт").contains("Гайка");
    }

    @Test
    void wrapsArrayParameterAsDataSource() throws IOException {
        ReportFile file = service.render(ReportRequest.template(WITH_ITEMS)
                .param("heading", "Массив")
                .param("items", new Item[] {new Item("Шайба", 7)})
                .format(ReportFormat.XLSX));
        assertThat(zipEntryText(file.content(), "xl/sharedStrings.xml")).contains("Шайба");
    }

    @Test
    void dropsRowsOverMaxRowsAndWarns(CapturedOutput output) throws IOException {
        ReportService limitedService = createService(List.of(), 1);
        ReportFile file = limitedService.render(ReportRequest.template(HELLO)
                .data(List.of(new Row("Первый", BigDecimal.ONE, Instant.now()),
                        new Row("Второй", BigDecimal.TEN, Instant.now())))
                .param("title", "Лимит").format(ReportFormat.XLSX));
        String strings = zipEntryText(file.content(), "xl/sharedStrings.xml");
        assertThat(strings).contains("Первый").doesNotContain("Второй");
        assertThat(output.getAll())
                .contains("строк больше лимита argent.reports.max-rows=1, лишние отброшены");
    }

    @Test
    @SuppressWarnings("unchecked")
    void wrapsConnectionFailureIntoRenderException() throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new java.sql.SQLException("нет соединения"));
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(broken);
        JasperReportsContext context = ReportJasperContext.create("DejaVu Sans");
        ReportTemplateCache templates = new ReportTemplateCache(new DefaultResourceLoader(), context, false);
        ReportProperties properties = new ReportProperties(List.of(), 10, Duration.ofSeconds(60),
                Locale.forLanguageTag("ru-RU"), "DejaVu Sans", true);
        ReportService sqlService = new ReportService(templates, context, provider, properties);
        assertThatThrownBy(() -> sqlService.render(ReportRequest.template("classpath:reports/sql-orders.jrxml")
                .param("from", LocalDate.of(2026, 1, 1)).param("to", LocalDate.of(2026, 12, 31))
                .format(ReportFormat.PDF)))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("Не удалось получить соединение с БД для отчёта");
    }

    @Test
    void rendersFromParametersOnlyWhenNoData() {
        ReportFile file = service.render(ReportRequest.template(WITH_ITEMS)
                .param("heading", "Без данных").format(ReportFormat.PDF));
        assertThat(file.content().length).isGreaterThan(200);
        assertThat(file.fileName()).isEqualTo("with-items.pdf");
    }

    @Test
    void rejectsUnknownParameter() {
        assertThatThrownBy(() -> service.render(ReportRequest.template(HELLO)
                .param("nope", 1).format(ReportFormat.PDF)))
                .isInstanceOf(ReportParameterException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("hello");
    }

    @Test
    void convertsParameterToDeclaredClass() {
        ReportFile file = service.render(ReportRequest.template(HELLO)
                .data(List.of()).param("title", 42).format(ReportFormat.PDF));
        assertThat(new String(file.content(), 0, 5, US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void rejectsInconvertibleParameter() {
        assertThatThrownBy(() -> service.render(ReportRequest.template(WITH_ITEMS)
                .param("items", LocalDate.of(2026, 11, 9)).format(ReportFormat.PDF)))
                .isInstanceOf(ReportParameterException.class)
                .hasMessageContaining("Параметр \"items\"");
    }

    @Test
    void rejectsMissingFormat() {
        assertThatThrownBy(() -> service.render(helloWithIvan()))
                .isInstanceOf(ReportParameterException.class)
                .hasMessageContaining("формат");
    }

    @Test
    void shouldSanitizeSheetName() throws IOException {
        ReportFile file = service.render(helloWithIvan()
                .fileName("Очень длинное имя файла отчёта [черновик]: версия 2/3").format(ReportFormat.XLSX));
        String workbook = zipEntryText(file.content(), "xl/workbook.xml");
        Matcher matcher = Pattern.compile("<sheet name=\"([^\"]*)\"").matcher(workbook);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isNotBlank().hasSizeLessThanOrEqualTo(31)
                .doesNotContainPattern("[\\[\\]:*?/\\\\]");
    }

    @Test
    void shouldRenderConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<ReportFile>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> service.render(helloWithIvan().format(ReportFormat.PDF))));
            }
            for (Future<ReportFile> future : futures) {
                assertThat(new String(future.get().content(), 0, 5, US_ASCII)).isEqualTo("%PDF-");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsDataForSqlTemplate(@TempDir Path dir) throws IOException {
        Path sql = dir.resolve("sql-report.jrxml");
        Files.writeString(sql, """
                <?xml version="1.0" encoding="UTF-8"?>
                <jasperReport name="sql-report" pageWidth="595" pageHeight="842" columnWidth="515" leftMargin="40" rightMargin="40" topMargin="50" bottomMargin="50">
                    <query language="sql"><![CDATA[select 1 as n]]></query>
                    <field name="n" class="java.lang.Integer"/>
                    <title height="20"/>
                </jasperReport>
                """);
        assertThatThrownBy(() -> service.render(ReportRequest.template(sql.toString())
                .data(List.of(new Item("Болт", 1))).format(ReportFormat.PDF)))
                .isInstanceOf(ReportParameterException.class)
                .hasMessageContaining("sql-report")
                .hasMessageContaining("data(...)");
    }

    @Test
    void preloadCompilesConfiguredTemplates() {
        assertThatCode(() -> createService(List.of("classpath:reports/hello.jrxml")).preload())
                .doesNotThrowAnyException();
    }

    private static boolean pdfHasFont(byte[] pdf, String fontName) {
        return new String(pdf, StandardCharsets.ISO_8859_1).contains(fontName);
    }
}
