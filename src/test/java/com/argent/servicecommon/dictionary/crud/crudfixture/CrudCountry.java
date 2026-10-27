package com.argent.servicecommon.dictionary.crud.crudfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.*;

/** Тестовая сущность для проверки типизированного CRUD-сервиса. */
@Entity
@Table(name = "crud_countries", uniqueConstraints = @UniqueConstraint(name = "uq_crud_countries_code", columnNames = "code"))
@ArgentDictionary(name = "Проверка CRUD: CrudCountry", dtoClass = CrudDto.class)
public class CrudCountry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 3)
    private String code;

    /** Возвращает значение поля id. */
    public Long getId() { return id; }

    /** Изменяет значение поля id. */
    public void setId(Long id) { this.id = id; }

    /** Возвращает значение поля name. */
    public String getName() { return name; }

    /** Изменяет значение поля name. */
    public void setName(String name) { this.name = name; }

    /** Возвращает значение поля code. */
    public String getCode() { return code; }

    /** Изменяет значение поля code. */
    public void setCode(String code) { this.code = code; }

}
