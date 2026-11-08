package com.argent.servicecommon.security.adminfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Тестовый справочник для проверки каталога грантов. */
@Entity
@Table(name = "admin_catalog_item")
@ArgentDictionary(name = "Элементы каталога", dtoClass = AdminCatalogItemDto.class)
public class AdminCatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Возвращает идентификатор. */
    public Long getId() {
        return id;
    }

    /** Изменяет идентификатор. */
    public void setId(Long id) {
        this.id = id;
    }

    /** Возвращает название. */
    public String getName() {
        return name;
    }

    /** Изменяет название. */
    public void setName(String name) {
        this.name = name;
    }
}
