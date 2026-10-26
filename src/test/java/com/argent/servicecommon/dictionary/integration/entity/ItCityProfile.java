package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItCityProfileDto;
import jakarta.persistence.*;

/** Проверяет владеющую сторону OneToOne и уникальный внешний ключ. */
@Entity
@Table(name = "it_city_profiles")
@ArgentDictionary(name = "Тестовые профили городов", dtoClass = ItCityProfileDto.class)
public class ItCityProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false, unique = true)
    private ItCity city;

    @Column(length = 200)
    private String description;
}
