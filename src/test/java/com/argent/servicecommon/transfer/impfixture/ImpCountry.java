package com.argent.servicecommon.transfer.impfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Тестовый справочник стран: уникальное название, числа, дата, флаг и ссылка на регион. */
@Entity
@Table(name = "imp_country")
@ArgentDictionary(name = "Страны (импорт)", dtoClass = ImpCountryDto.class)
@Getter
@Setter
public class ImpCountry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String code;

    private Integer population;

    @Column(precision = 14, scale = 2)
    private BigDecimal area;

    private LocalDate founded;

    private Boolean active;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private ImpRegion region;
}
