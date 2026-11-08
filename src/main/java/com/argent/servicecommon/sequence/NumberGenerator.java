package com.argent.servicecommon.sequence;

import com.argent.servicecommon.sequence.entity.CommonSequence;
import com.argent.servicecommon.sequence.entity.ResetPeriod;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Выдача следующего номера документа по счётчику {@link CommonSequence}.
 *
 * <p>Счётчик меняется в обход JPA двумя SQL-операторами в одной транзакции: {@code SELECT … FOR
 * UPDATE} блокирует строку и читает период сброса, затем {@code UPDATE … RETURNING} увеличивает
 * счётчик или сбрасывает его на 1, если ключ периода сменился. Блокировка строки держится до
 * конца транзакции вызывающего кода, поэтому параллельные запросы по одному коду выстраиваются в
 * очередь: номера не дублируются и, если транзакция вызывающего фиксируется, идут без пропусков
 * (откат транзакции откатывает и выдачу номера).</p>
 *
 * <p>Таблица указывается без схемы и разрешается через {@code search_path} подключения — так же,
 * как библиотечные таблицы находят миграции и справочники.</p>
 */
public class NumberGenerator {

    private static final String LOCK_SQL =
            "SELECT reset_period, pattern FROM common_sequence WHERE code = ? FOR UPDATE";
    private static final String NEXT_SQL = "UPDATE common_sequence"
            + " SET counter = CASE WHEN period_key = ? THEN counter + 1 ELSE 1 END, period_key = ?"
            + " WHERE code = ? RETURNING counter, pattern";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NumberGenerator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Выдаёт следующий номер: {@code String.format(pattern, ключ периода, счётчик)}, например
     * {@code ORD-%1$s-%2$05d} → {@code ORD-2026-11-00001}.
     *
     * <p>Выполняется в транзакции вызывающего кода (или в собственной, если её нет). Отказ не помечает
     * транзакцию вызывающего на откат: исключение можно поймать и продолжить работу.</p>
     *
     * @param code код счётчика
     * @return сформированный номер
     * @throws EntityNotFoundException если счётчика с таким кодом нет (404)
     * @throws IllegalStateException   если вызов идёт в read-only транзакции или сохранённый шаблон
     *                                 недопустим (409)
     */
    @Transactional(noRollbackFor = {EntityNotFoundException.class, IllegalStateException.class,
            AccessDeniedException.class})
    public String next(String code) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("NumberGenerator.next нельзя вызывать в read-only транзакции");
        }
        List<Locked> periods = jdbc.query(LOCK_SQL,
                (rs, rowNum) -> new Locked(rs.getString("reset_period"), rs.getString("pattern")), code);
        if (periods.isEmpty()) {
            throw new EntityNotFoundException("Счётчик не найден: " + code);
        }
        // Шаблон проверяется до увеличения счётчика: при ошибке номер не расходуется.
        String pattern = periods.get(0).pattern();
        if (!PatternValidator.isValid(pattern)) {
            throw new IllegalStateException("Недопустимый шаблон номера счётчика " + code + ": " + pattern);
        }
        String periodKey = ResetPeriod.valueOf(periods.get(0).resetPeriod()).periodKey(LocalDate.now(clock));
        Map<String, Object> row = jdbc.queryForMap(NEXT_SQL, periodKey, periodKey, code);
        long counter = ((Number) row.get("counter")).longValue();
        return String.format((String) row.get("pattern"), periodKey, counter);
    }

    /** Заблокированная строка счётчика: период сброса и шаблон. */
    private record Locked(String resetPeriod, String pattern) {
    }
}
