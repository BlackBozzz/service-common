package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.TestComplexDto;
import com.argent.servicecommon.dictionary.mapper.fixture.types.TestComplexState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/**
 * Сложная тестовая JPA-сущность для проверки универсального DTO-маппера.
 *
 * <p>Содержит скалярные типы, даты, перечисление, массив байтов,
 * встраиваемый объект, коллекцию значений и все основные виды
 * односторонних JPA-связей.</p>
 */
@Entity
@Table(name = "dto_complex_records")
@ArgentDictionary(name = "Тест DTO: сложные записи", dtoClass = TestComplexDto.class)
@Getter
@Setter
@NoArgsConstructor
public class TestComplexEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    private boolean enabled;
    private Boolean verified;
    private byte level;
    private Short shortValue;
    private Integer count;
    private long total;
    private Float ratio;
    private Double score;

    @Column(precision = 18, scale = 2)
    private BigDecimal price;

    @Column(precision = 38, scale = 0)
    private BigInteger bigCounter;

    private UUID externalId;
    private Character grade;
    private LocalDate startDate;
    private LocalTime startTime;
    private LocalDateTime registeredAt;
    private Instant occurredAt;
    private OffsetDateTime offsetAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TestComplexState status;

    /** Обычный бинарный столбец, не PostgreSQL large object. */
    private byte[] payload;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    private TestCountryEntity country;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "featured_tag_id")
    private TestTagEntity featuredTag;

    @ManyToMany
    @JoinTable(
            name = "dto_complex_tags",
            joinColumns = @JoinColumn(name = "complex_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<TestTagEntity> tags = new LinkedHashSet<>();

    /** Владеющая сторона OneToMany с таблицей связей. */
    @OneToMany
    @JoinTable(
            name = "dto_complex_children",
            joinColumns = @JoinColumn(name = "complex_id"),
            inverseJoinColumns = @JoinColumn(name = "child_id")
    )
    private Set<TestChildEntity> children = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "dto_complex_aliases", joinColumns = @JoinColumn(name = "complex_id"))
    @OrderColumn(name = "alias_position")
    @Column(name = "alias_value", nullable = false)
    private List<String> aliases = new ArrayList<>();

    @Embedded
    private TestAddressEmbeddable address;
}
