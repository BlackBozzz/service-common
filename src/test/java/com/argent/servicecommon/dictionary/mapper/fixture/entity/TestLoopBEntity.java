package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.TestLoopBDto;
import jakarta.persistence.*;

/** Тест циклической структуры DTO. */
@Entity
@Table(name = "dto_loop_b")
@ArgentDictionary(name = "Тест DTO: цикл Б", dtoClass = TestLoopBDto.class)
public class TestLoopBEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne private TestLoopAEntity a;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public TestLoopAEntity getA() { return a; }
    public void setA(TestLoopAEntity a) { this.a = a; }
}
