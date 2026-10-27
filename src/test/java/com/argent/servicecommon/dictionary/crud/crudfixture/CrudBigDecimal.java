package com.argent.servicecommon.dictionary.crud.crudfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.*;

import java.math.BigDecimal;

/** Тестовая сущность для проверки типизированного CRUD-сервиса. */
@Entity
@Table(name = "crud_big_decimals")
@ArgentDictionary(name = "Проверка CRUD: CrudBigDecimal", dtoClass = CrudDto.class)
public class CrudBigDecimal {
    @Id
    private BigDecimal id;

    @Column(nullable = false)
    private String name;

    /** Возвращает значение поля id. */
    public BigDecimal getId() { return id; }

    /** Изменяет значение поля id. */
    public void setId(BigDecimal id) { this.id = id; }

    /** Возвращает значение поля name. */
    public String getName() { return name; }

    /** Изменяет значение поля name. */
    public void setName(String name) { this.name = name; }

}
