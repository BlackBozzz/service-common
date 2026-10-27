package com.argent.servicecommon.dictionary.crud.crudfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.*;

/** Тестовая сущность для проверки типизированного CRUD-сервиса. */
@Entity
@Table(name = "crud_cities")
@ArgentDictionary(name = "Проверка CRUD: CrudCity", dtoClass = CrudDto.class)
public class CrudCity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private CrudCountry country;

    /** Возвращает значение поля id. */
    public Long getId() { return id; }

    /** Изменяет значение поля id. */
    public void setId(Long id) { this.id = id; }

    /** Возвращает значение поля name. */
    public String getName() { return name; }

    /** Изменяет значение поля name. */
    public void setName(String name) { this.name = name; }

    /** Возвращает значение поля country. */
    public CrudCountry getCountry() { return country; }

    /** Изменяет значение поля country. */
    public void setCountry(CrudCountry country) { this.country = country; }

}
