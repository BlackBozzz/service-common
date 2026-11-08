package com.argent.servicecommon.sequence;

import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.sequence.entity.CommonSequence;
import com.argent.servicecommon.sequence.entity.ResetPeriod;
import com.argent.servicecommon.sequence.repository.CommonSequenceRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Нумерация документов: формат по шаблону, сброс по периоду (в том числе ISO-неделя),
 * отсутствие пропусков и дублей при конкурентной выдаче, валидация шаблона в справочнике.
 */
@SpringBootTest(
        classes = {SecurityTestApplication.class, NumberGeneratorIntegrationTest.ClockConfig.class,
                NumberGeneratorIntegrationTest.NextController.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class NumberGeneratorIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 7);
    private static final int THREADS = 20;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_sequence");
        // 20 потоков должны получить соединения одновременно, иначе тест конкуренции вырождается.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "21");
    }

    @Autowired private NumberGenerator generator;
    @Autowired private CommonSequenceRepository repository;
    @Autowired private MutableClock clock;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        repository.deleteAllInBatch();
        clock.set(START);
        sequence("ORDER", "ORD-%1$s-%2$05d", ResetPeriod.MONTHLY);
        sequence("WEEK", "W%1$s-%2$d", ResetPeriod.WEEKLY);
    }

    @Test
    void shouldFormatWithPeriodAndCounter() {
        assertThat(generator.next("ORDER")).isEqualTo("ORD-2026-11-00001");
        assertThat(generator.next("ORDER")).isEqualTo("ORD-2026-11-00002");

        CommonSequence stored = repository.findByCode("ORDER").orElseThrow();
        assertThat(stored.getCounter()).isEqualTo(2L);
        assertThat(stored.getPeriodKey()).isEqualTo("2026-11");
    }

    @Test
    void shouldResetOnNewPeriod() {
        assertThat(generator.next("ORDER")).isEqualTo("ORD-2026-11-00001");
        assertThat(generator.next("ORDER")).isEqualTo("ORD-2026-11-00002");

        clock.set(LocalDate.of(2026, 12, 1));
        assertThat(generator.next("ORDER")).isEqualTo("ORD-2026-12-00001");
        assertThat(generator.next("ORDER")).isEqualTo("ORD-2026-12-00002");
    }

    @Test
    void shouldUseIsoWeek() {
        clock.set(LocalDate.of(2025, 12, 29));
        assertThat(generator.next("WEEK")).isEqualTo("W2026-W01-1");
        clock.set(LocalDate.of(2026, 1, 1));
        assertThat(generator.next("WEEK")).isEqualTo("W2026-W01-2");
        clock.set(LocalDate.of(2026, 1, 5));
        assertThat(generator.next("WEEK")).isEqualTo("W2026-W02-1");
    }

    @Test
    void rollbackReleasesNumber() {
        String first = transactions.execute(status -> {
            String number = generator.next("ORDER");
            status.setRollbackOnly();
            return number;
        });

        assertThat(first).isEqualTo("ORD-2026-11-00001");
        String second = transactions.execute(status -> generator.next("ORDER"));
        assertThat(second).isEqualTo(first);
        assertThat(repository.findByCode("ORDER").orElseThrow().getCounter()).isEqualTo(1L);
    }

    @Test
    void shouldBeGapFreeUnderConcurrency() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isGreaterThan(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<String> task = () -> {
                    start.await();
                    return transactions.execute(status -> generator.next("ORDER"));
                };
                futures.add(executor.submit(task));
            }
            start.countDown();

            List<String> numbers = new ArrayList<>();
            for (Future<String> future : futures) {
                numbers.add(future.get());
            }
            assertThat(numbers).hasSize(THREADS).doesNotHaveDuplicates()
                    .allMatch(number -> number.startsWith("ORD-2026-11-"));
            assertThat(numbers.stream().map(number -> Long.parseLong(number.substring("ORD-2026-11-".length()))))
                    .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, THREADS).boxed().toList());
        } finally {
            executor.shutdownNow();
        }
        assertThat(repository.findByCode("ORDER").orElseThrow().getCounter()).isEqualTo((long) THREADS);
    }

    @Test
    void shouldRejectInvalidPattern() throws Exception {
        String token = adminToken();
        mvc.perform(post("/api/argent-dictionary/common_sequence/records")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BAD\",\"pattern\":\"NO-COUNTER\",\"resetPeriod\":\"NEVER\",\"counter\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("pattern"))
                .andExpect(jsonPath("$.errors[0].message").value("Недопустимый шаблон номера: NO-COUNTER"));
        assertThat(repository.findByCode("BAD")).isEmpty();

        mvc.perform(post("/api/argent-dictionary/common_sequence/records")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"INVOICE\",\"pattern\":\"INV-%1$s-%2$04d\",\"resetPeriod\":\"YEARLY\","
                                + "\"counter\":0}"))
                .andExpect(status().isCreated());
        assertThat(generator.next("INVOICE")).isEqualTo("INV-2026-0001");
    }

    @Test
    void shouldFailForUnknownCode() throws Exception {
        assertThatThrownBy(() -> generator.next("MISSING"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Счётчик не найден: MISSING");

        mvc.perform(post("/test/sequence/MISSING/next")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Счётчик не найден: MISSING"));
    }

    @Test
    void adminEditShouldNotRewindCounter() throws Exception {
        generator.next("ORDER");
        generator.next("ORDER");
        String token = adminToken();
        String id = repository.findByCode("ORDER").orElseThrow().getId().toString();
        String body = mvc.perform(get("/api/argent-dictionary/common_sequence/records/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ObjectNode record = (ObjectNode) JsonMapper.shared().readTree(body);
        record.put("pattern", "Z-%1$s-%2$05d");
        record.put("counter", 0);
        record.put("periodKey", "1999-01");

        mvc.perform(put("/api/argent-dictionary/common_sequence/records/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(record.toString()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT counter FROM common_sequence WHERE code = 'ORDER'", Long.class))
                .isEqualTo(2L);
        assertThat(generator.next("ORDER")).isEqualTo("Z-2026-11-00003");
        assertThat(jdbc.queryForList("SELECT field FROM common_audit_entry WHERE entity_type = 'common_sequence'"
                + " AND entity_id = ? AND action = 'UPDATE'", String.class, id)).containsExactly("pattern");
    }

    @Test
    void shouldCreateSequenceWithoutCounterInBody() throws Exception {
        mvc.perform(post("/api/argent-dictionary/common_sequence/records")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ACT\",\"pattern\":\"A-%2$d\",\"resetPeriod\":\"NEVER\"}"))
                .andExpect(status().isCreated());
        assertThat(generator.next("ACT")).isEqualTo("A-1");
    }

    @Test
    void shouldRejectInvalidStoredPattern() throws Exception {
        jdbc.update("UPDATE common_sequence SET pattern = 'NO-COUNTER' WHERE code = 'ORDER'");

        assertThatThrownBy(() -> generator.next("ORDER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Недопустимый шаблон номера счётчика ORDER: NO-COUNTER");
        mvc.perform(post("/test/sequence/ORDER/next")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Недопустимый шаблон номера счётчика ORDER: NO-COUNTER"));
        assertThat(jdbc.queryForObject("SELECT counter FROM common_sequence WHERE code = 'ORDER'", Long.class))
                .isZero();
    }

    @Test
    void shouldRejectReadOnlyTransaction() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.execute(status -> generator.next("ORDER")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NumberGenerator.next нельзя вызывать в read-only транзакции");
    }

    @Test
    void callerMayCatchUnknownCodeAndCommit() {
        String number = transactions.execute(status -> {
            assertThatThrownBy(() -> generator.next("MISSING")).isInstanceOf(EntityNotFoundException.class);
            return generator.next("ORDER");
        });

        assertThat(number).isEqualTo("ORD-2026-11-00001");
        assertThat(repository.findByCode("ORDER").orElseThrow().getCounter()).isEqualTo(1L);
    }

    @Test
    void nextShouldNotWriteAuditRows() {
        jdbc.update("DELETE FROM common_audit_entry");

        generator.next("ORDER");
        generator.next("ORDER");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM common_audit_entry", Long.class)).isZero();
    }

    private void sequence(String code, String pattern, ResetPeriod period) {
        CommonSequence entity = new CommonSequence();
        entity.setCode(code);
        entity.setPattern(pattern);
        entity.setResetPeriod(period);
        entity.setCounter(0L);
        repository.save(entity);
    }

    private String adminToken() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.shared().readTree(body).get("token").asString();
    }

    /** Подменяет системные часы управляемыми — автоконфигурация нумерации отступает. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        MutableClock mutableClock() {
            return MutableClock.at(START);
        }
    }

    /** Тестовая точка входа, через которую приложение запрашивает номер. */
    @RestController
    static class NextController {

        private final NumberGenerator generator;

        NextController(NumberGenerator generator) {
            this.generator = generator;
        }

        @PostMapping("/test/sequence/{code}/next")
        String next(@PathVariable("code") String code) {
            return generator.next(code);
        }
    }
}
