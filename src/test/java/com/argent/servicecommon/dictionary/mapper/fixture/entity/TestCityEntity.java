package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.TestCityDto;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

/** Тестовый город со связями и встраиваемым объектом. */
@Entity
@Table(name = "dto_cities")
@ArgentDictionary(name = "Тест DTO: города", dtoClass = TestCityDto.class)
public class TestCityEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80)
    private String name;
    @Column(nullable = false)
    private int population;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private TestCountryEntity country;
    @ManyToMany
    @JoinTable(name = "dto_city_tags", joinColumns = @JoinColumn(name = "city_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<TestTagEntity> tags = new HashSet<>();
    @ElementCollection
    @CollectionTable(name = "dto_city_aliases", joinColumns = @JoinColumn(name = "city_id"))
    @Column(name = "alias")
    private Set<String> aliases = new HashSet<>();
    @Embedded
    private TestAddressEmbeddable address;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getPopulation() { return population; }
    public void setPopulation(int population) { this.population = population; }
    public TestCountryEntity getCountry() { return country; }
    public void setCountry(TestCountryEntity country) { this.country = country; }
    public Set<TestTagEntity> getTags() { return tags; }
    public void setTags(Set<TestTagEntity> tags) { this.tags = tags; }
    public Set<String> getAliases() { return aliases; }
    public void setAliases(Set<String> aliases) { this.aliases = aliases; }
    public TestAddressEmbeddable getAddress() { return address; }
    public void setAddress(TestAddressEmbeddable address) { this.address = address; }
}
