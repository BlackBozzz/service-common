package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItCategoryDto;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Проверяет UUID и обратную сторону ManyToMany. */
@Entity
@Table(name = "it_categories")
@ArgentDictionary(name = "Тестовые категории", dtoClass = ItCategoryDto.class)
public class ItCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @ManyToMany(mappedBy = "categories")
    private Set<ItCity> cities = new HashSet<>();
}
