package com.argent.servicecommon.transfer.impfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Тестовый справочник регионов для проверки импорта ссылок по подписи. */
@Entity
@Table(name = "imp_region")
@ArgentDictionary(name = "Регионы (импорт)", dtoClass = ImpRegionDto.class)
@Getter
@Setter
public class ImpRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;
}
