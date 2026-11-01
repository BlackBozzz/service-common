package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.TestLoopADto;
import jakarta.persistence.*;

/** Тест циклической структуры DTO. */
@Entity
@Table(name = "dto_loop_a")
@ArgentDictionary(name = "Тест DTO: цикл А", dtoClass = TestLoopADto.class)
public class TestLoopAEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne private TestLoopBEntity b;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public TestLoopBEntity getB() { return b; }
    public void setB(TestLoopBEntity b) { this.b = b; }
}
