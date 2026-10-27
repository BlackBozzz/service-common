package com.argent.servicecommon.dictionary.crud.crudfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.*;
import java.math.BigInteger;

/** Тестовая сущность для проверки типизированного CRUD-сервиса. */
@Entity
@Table(name = "crud_big_integers")
@ArgentDictionary(name = "Проверка CRUD: CrudBigInteger", dtoClass = CrudDto.class)
public class CrudBigInteger {
    @Id
    private BigInteger id;

    @Column(nullable = false)
    private String name;

    /** Возвращает значение поля id. */
    public BigInteger getId() { return id; }

    /** Изменяет значение поля id. */
    public void setId(BigInteger id) { this.id = id; }

    /** Возвращает значение поля name. */
    public String getName() { return name; }

    /** Изменяет значение поля name. */
    public void setName(String name) { this.name = name; }

}
