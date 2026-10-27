package com.argent.servicecommon.dictionary.crud.crudfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.*;

import java.util.UUID;

/** Тестовая сущность для проверки типизированного CRUD-сервиса. */
@Entity
@Table(name = "crud_categories")
@ArgentDictionary(name = "Проверка CRUD: CrudCategory", dtoClass = CrudDto.class)
public class CrudCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Возвращает значение поля id. */
    public UUID getId() { return id; }

    /** Изменяет значение поля id. */
    public void setId(UUID id) { this.id = id; }

    /** Возвращает значение поля name. */
    public String getName() { return name; }

    /** Изменяет значение поля name. */
    public void setName(String name) { this.name = name; }

}
