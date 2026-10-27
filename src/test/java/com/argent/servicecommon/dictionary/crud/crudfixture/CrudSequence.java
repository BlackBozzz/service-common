package com.argent.servicecommon.dictionary.crud.crudfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.*;

/** Тестовая сущность для проверки типизированного CRUD-сервиса. */
@Entity
@Table(name = "crud_sequences")
@ArgentDictionary(name = "Проверка CRUD: CrudSequence", dtoClass = CrudDto.class)
public class CrudSequence {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "crud_sequence_generator")
    @SequenceGenerator(name = "crud_sequence_generator", sequenceName = "crud_sequence_id_seq", initialValue = 1, allocationSize = 5)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Возвращает значение поля id. */
    public Long getId() { return id; }

    /** Изменяет значение поля id. */
    public void setId(Long id) { this.id = id; }

    /** Возвращает значение поля name. */
    public String getName() { return name; }

    /** Изменяет значение поля name. */
    public void setName(String name) { this.name = name; }

}
