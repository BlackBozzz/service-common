package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.TestCountryDto;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

/** Тестовая страна. */
@Entity
@Table(name = "dto_countries")
@ArgentDictionary(name = "Тест DTO: страны", dtoClass = TestCountryDto.class)
public class TestCountryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80)
    private String name;
    @OneToMany(mappedBy = "country")
    private Set<TestCityEntity> cities = new HashSet<>();
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Set<TestCityEntity> getCities() { return cities; }
    public void setCities(Set<TestCityEntity> cities) { this.cities = cities; }
}
