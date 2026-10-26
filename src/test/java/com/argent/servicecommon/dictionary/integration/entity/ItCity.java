package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItCityDto;
import jakarta.persistence.*;
import org.hibernate.annotations.Formula;
import java.util.HashSet;
import java.util.Set;

/**
 * Проверяет ManyToOne, двусторонние связи, Embedded, Formula,
 * ElementCollection, ManyToMany, составной UNIQUE и обычный индекс.
 */
@Entity
@Table(name = "it_cities",
       uniqueConstraints = @UniqueConstraint(name = "uq_it_cities_country_code", columnNames = {"country_id", "code"}),
       indexes = @Index(name = "ix_it_cities_name", columnList = "name"))
@ArgentDictionary(name = "Тестовые города", dtoClass = ItCityDto.class)
public class ItCity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 15)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private ItCountry country;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "street", column = @Column(name = "street", length = 120)),
            @AttributeOverride(name = "postalCode", column = @Column(name = "postal_code", length = 15))
    })
    private ItAddress address;

    // Вычисляемый атрибут не должен превращаться в физическую колонку.
    @Formula("upper(name)")
    private String upperName;

    @ElementCollection
    @CollectionTable(name = "it_city_aliases", joinColumns = @JoinColumn(name = "city_id"))
    @Column(name = "alias", nullable = false, length = 100)
    private Set<String> aliases = new HashSet<>();

    @ManyToMany
    @JoinTable(name = "it_city_categories",
            joinColumns = @JoinColumn(name = "city_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<ItCategory> categories = new HashSet<>();

    @OneToOne(mappedBy = "city")
    private ItCityProfile profile;
}
