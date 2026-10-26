package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItCycleADto;
import jakarta.persistence.*;

/** Первая сторона циклической связи между двумя новыми справочниками. */
@Entity
@Table(name = "it_cycle_a")
@ArgentDictionary(name = "Тестовый цикл А", dtoClass = ItCycleADto.class)
public class ItCycleA {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "b_id")
    private ItCycleB other;
}
