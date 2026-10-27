package com.argent.servicecommon.dictionary.crud.crudfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.*;

/** Тестовая сущность для проверки типизированного CRUD-сервиса. */
@Entity
@Table(name = "crud_bytes")
@ArgentDictionary(name = "Проверка CRUD: CrudByte", dtoClass = CrudDto.class)
public class CrudByte {
    @Id
    private Byte id;

    @Column(nullable = false)
    private String name;

    /** Возвращает значение поля id. */
    public Byte getId() { return id; }

    /** Изменяет значение поля id. */
    public void setId(Byte id) { this.id = id; }

    /** Возвращает значение поля name. */
    public String getName() { return name; }

    /** Изменяет значение поля name. */
    public void setName(String name) { this.name = name; }

}
