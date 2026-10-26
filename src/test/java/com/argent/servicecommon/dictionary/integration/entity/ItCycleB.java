package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItCycleBDto;
import jakarta.persistence.*;

/** Вторая сторона циклической связи между двумя новыми справочниками. */
@Entity
@Table(name = "it_cycle_b")
@ArgentDictionary(name = "Тестовый цикл Б", dtoClass = ItCycleBDto.class)
public class ItCycleB {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "a_id")
    private ItCycleA other;
}
