package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.TestTagDto;
import jakarta.persistence.*;

/** Тестовая категория. */
@Entity
@Table(name = "dto_tags")
@ArgentDictionary(name = "Тест DTO: теги", dtoClass = TestTagDto.class)
public class TestTagEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
