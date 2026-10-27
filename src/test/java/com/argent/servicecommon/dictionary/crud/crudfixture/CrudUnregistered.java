package com.argent.servicecommon.dictionary.crud.crudfixture;

import jakarta.persistence.*;

/** JPA-сущность, специально не зарегистрированная как справочник. */
@Entity
@Table(name = "crud_unregistered")
public class CrudUnregistered {
    @Id
    private Long id;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
