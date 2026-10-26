package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItCountryDto;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Проверяет IDENTITY, длины строк, обязательность полей, UNIQUE на колонке
 * и обратную сторону связи один-ко-многим.
 */
@Entity
@Table(name = "it_countries")
@ArgentDictionary(name = "Тестовые страны", dtoClass = ItCountryDto.class)
public class ItCountry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 3, unique = true)
    private String code;

    @OneToMany(mappedBy = "country")
    private Set<ItCity> cities = new HashSet<>();
}
