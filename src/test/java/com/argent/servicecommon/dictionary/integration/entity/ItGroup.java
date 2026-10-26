package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItGroupDto;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

/** Проверяет однонаправленную OneToMany через отдельную JoinTable. */
@Entity
@Table(name = "it_groups")
@ArgentDictionary(name = "Тестовые группы", dtoClass = ItGroupDto.class)
public class ItGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String title;

    @OneToMany
    @JoinTable(name = "it_group_cities",
            joinColumns = @JoinColumn(name = "group_id"),
            inverseJoinColumns = @JoinColumn(name = "city_id"))
    private Set<ItCity> cities = new HashSet<>();
}
