package com.argent.servicecommon.dictionary.crud.crudfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Сущность с типом ключа, не поддерживаемым преобразователем CRUD-сервиса. */
@Entity
@Table(name = "crud_unsupported_id")
public class CrudUnsupportedId {
    @Id
    private Character id;

    public Character getId() { return id; }
    public void setId(Character id) { this.id = id; }
}
