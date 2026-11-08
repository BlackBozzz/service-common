package com.argent.servicecommon.audit.audfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Тестовая сущность без {@code @Audited}: её изменения в журнал не попадают. */
@Entity
@Table(name = "aud_plain")
@Getter
@Setter
public class AudPlain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;
}
