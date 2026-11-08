package com.argent.servicecommon.audit.audfixture;

import com.argent.servicecommon.audit.Audited;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Аудируемая сущность без {@code @Table}: таблица {@code aud_no_table} по стратегии именования. */
@Entity
@Audited
@Getter
@Setter
public class AudNoTable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;
}
