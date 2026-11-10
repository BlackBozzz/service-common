package com.argent.servicecommon.report;

import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.zaxxer.hikari.HikariDataSource;
import net.sf.jasperreports.engine.JasperReportsContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static com.argent.servicecommon.report.ReportFormat.PDF;
import static com.argent.servicecommon.report.ReportFormat.XLSX;
import static com.argent.servicecommon.report.ReportTestFiles.zipEntryText;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL-шаблоны: соединение из DataSource, параметры, таймаут запроса, лимит строк и транзакция вызывающего. */
@SpringBootTest(classes = SecurityTestApplication.class)
class ReportServiceSqlPathIntegrationTest {

    private static final String SQL_ORDERS = "classpath:reports/sql-orders.jrxml";
    private static final String SQL_SLEEP = "classpath:reports/sql-sleep.jrxml";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_report");
        registry.add("argent.reports.query-timeout", () -> "1s");
    }

    @Autowired private ReportService service;
    @Autowired private ReportTemplateCache templates;
    @Autowired private JasperReportsContext context;
    @Autowired private ObjectProvider<DataSource> dataSourceProvider;
    @Autowired private ReportProperties properties;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    @BeforeEach
    void prepareOrders() {
        jdbc.execute("DROP TABLE IF EXISTS report_orders");
        jdbc.execute("CREATE TABLE report_orders (number varchar, customer varchar, total numeric, "
                + "created_at timestamptz)");
        jdbc.update("INSERT INTO report_orders VALUES ('ORD-1', 'Иванов', 100.50, '2026-03-01 10:00:00+03')");
        jdbc.update("INSERT INTO report_orders VALUES ('ORD-2', 'Петров', 200, '2026-05-15 12:00:00+03')");
        jdbc.update("INSERT INTO report_orders VALUES ('ORD-OLD', 'Сидоров', 300, '2025-06-01 09:00:00+03')");
    }

    private static ReportRequest ordersOf2026() {
        return ReportRequest.template(SQL_ORDERS)
                .param("from", LocalDate.of(2026, 1, 1))
                .param("to", LocalDate.of(2026, 12, 31));
    }

    @Test
    void rendersSqlReportWithParameters() throws IOException {
        ReportFile file = service.render(ordersOf2026().format(XLSX));
        String strings = zipEntryText(file.content(), "xl/sharedStrings.xml");
        assertThat(strings).contains("ORD-1").contains("ORD-2").doesNotContain("ORD-OLD");
    }

    @Test
    void rendersSqlReportToPdf() {
        ReportFile file = service.render(ordersOf2026().format(PDF));
        assertThat(new String(file.content(), 0, 5, US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void rejectsDataForSqlTemplate() {
        assertThatThrownBy(() -> service.render(ordersOf2026().data(List.of()).format(PDF)))
                .isInstanceOf(ReportParameterException.class)
                .hasMessageContaining("SQL");
    }

    @Test
    void failsOnQueryTimeout() {
        assertThatThrownBy(() -> service.render(ReportRequest.template(SQL_SLEEP).format(PDF)))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("таймаут 1 с")
                .hasMessageContaining("SQLState 57014")
                .hasMessageContaining("sql-sleep")
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void limitsRowsByMaxRows() throws IOException {
        ReportProperties oneRow = new ReportProperties(List.of(), 1, properties.queryTimeout(),
                properties.locale(), properties.defaultFont(), properties.watchFiles());
        ReportService limited = new ReportService(templates, context, dataSourceProvider, oneRow);
        ReportFile file = limited.render(ordersOf2026().format(XLSX));
        String strings = zipEntryText(file.content(), "xl/sharedStrings.xml");
        assertThat(strings).contains("ORD-1").doesNotContain("ORD-2");
    }

    @Test
    void seesUncommittedRowsOfCallingTransaction() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO report_orders VALUES ('ORD-TX', 'Транзакция', 1, now())");
            ReportFile file = service.render(ReportRequest.template(SQL_ORDERS)
                    .param("from", LocalDate.now().minusDays(1))
                    .param("to", LocalDate.now().plusDays(2))
                    .format(XLSX));
            assertThat(xlsxStrings(file)).contains("ORD-TX");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_orders WHERE number = 'ORD-TX'", Long.class))
                .isZero();
    }

    @Test
    void doesNotTakeSecondConnectionInsideTransaction() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger samples = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            // Пока запрос отчёта спит в pg_sleep, фоновый поток замеряет занятые соединения пула.
            Thread sampler = Thread.ofPlatform().start(() -> {
                while (sampling.get()) {
                    maxActive.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
                    samples.incrementAndGet();
                    LockSupport.parkNanos(1_000_000);
                }
            });
            try {
                assertThatThrownBy(() -> service.render(ReportRequest.template(SQL_SLEEP).format(PDF)))
                        .isInstanceOf(ReportRenderException.class)
                        .hasMessageContaining("таймаут");
                assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            } finally {
                sampling.set(false);
                joinQuietly(sampler);
            }
            status.setRollbackOnly();
        });
        assertThat(samples.get()).isPositive();
        assertThat(maxActive.get()).isEqualTo(1);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String xlsxStrings(ReportFile file) {
        try {
            return zipEntryText(file.content(), "xl/sharedStrings.xml");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
