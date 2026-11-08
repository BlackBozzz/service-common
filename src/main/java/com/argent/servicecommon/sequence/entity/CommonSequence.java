package com.argent.servicecommon.sequence.entity;

import com.argent.servicecommon.audit.Audited;
import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.sequence.dto.CommonSequenceDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.util.UUID;

/**
 * Счётчик номеров документов (библиотечный справочник, таблица из V4).
 *
 * <p>{@link DynamicUpdate}: правка через админку обновляет только изменённые колонки и не перезаписывает
 * {@code counter}/{@code period_key}, которые параллельно меняет
 * {@link com.argent.servicecommon.sequence.NumberGenerator}.
 * Правки аудируются; выдача номеров идёт через JDBC и в журнал не попадает.</p>
 */
@Entity
@DynamicUpdate
@Audited
@Table(name = "common_sequence")
@ArgentDictionary(name = "Счётчики номеров", dtoClass = CommonSequenceDto.class)
@Getter
@Setter
@NoArgsConstructor
public class CommonSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Уникальный код счётчика. */
    @Column(nullable = false, unique = true, length = 100)
    private String code;

    /** Шаблон номера. */
    @Column(nullable = false)
    private String pattern;

    /** Период сброса счётчика. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reset_period", nullable = false, length = 20)
    private ResetPeriod resetPeriod = ResetPeriod.NEVER;

    /** Ключ периода, в котором выдано последнее значение. */
    @Column(name = "period_key", length = 50)
    private String periodKey;

    /** Последнее выданное значение. */
    @Column(nullable = false)
    private Long counter = 0L;
}
