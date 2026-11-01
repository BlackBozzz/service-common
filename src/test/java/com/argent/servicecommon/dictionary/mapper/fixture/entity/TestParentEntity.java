package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.TestParentDto;
import jakarta.persistence.*;
import java.util.Set;
import java.util.HashSet;

/** Родитель обратной коллекционной связи. */
@Entity
@Table(name = "dto_parents")
@ArgentDictionary(name = "Тест DTO: родители", dtoClass = TestParentDto.class)
public class TestParentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToMany(mappedBy = "parent") private Set<TestChildEntity> children = new HashSet<>();
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Set<TestChildEntity> getChildren() { return children; }
    public void setChildren(Set<TestChildEntity> children) { this.children = children; }
}
