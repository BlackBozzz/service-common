package com.argent.servicecommon.security.urlfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Тестовый справочник для проверки правила доступа к справочникам по URL. */
@Entity
@Table(name = "url_country")
@ArgentDictionary(name = "Страны (URL)", dtoClass = UrlCountryDto.class)
public class UrlCountry {

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
