package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.TestChildDto;
import jakarta.persistence.*;

/** Дочерняя сущность, владеющая FK. */
@Entity
@Table(name = "dto_children")
@ArgentDictionary(name = "Тест DTO: дети", dtoClass = TestChildDto.class)
public class TestChildEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne @JoinColumn(name = "parent_id") private TestParentEntity parent;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public TestParentEntity getParent() { return parent; }
    public void setParent(TestParentEntity parent) { this.parent = parent; }
}
