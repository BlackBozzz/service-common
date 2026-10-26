package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItRegionDto;
import jakarta.persistence.*;

/** Проверяет ASSIGNED — идентификатор без GeneratedValue. */
@Entity
@Table(name = "it_regions")
@ArgentDictionary(name = "Тестовые регионы", dtoClass = ItRegionDto.class)
public class ItRegion {
    @Id
    @Column(length = 12)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;
}
