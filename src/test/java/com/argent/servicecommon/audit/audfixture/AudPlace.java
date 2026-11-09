package com.argent.servicecommon.audit.audfixture;

import com.argent.servicecommon.audit.AuditIgnore;
import com.argent.servicecommon.audit.Audited;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Аудируемая сущность со встроенными объектами: адрес, скрытый секрет и вложенная точка. */
@Entity
@Audited
@Table(name = "aud_place")
@Getter
@Setter
public class AudPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    @Embedded
    private AudAddress address;

    /** Весь встроенный объект скрыт: каждое изменённое подполе пишется как {@code ***}. */
    @AuditIgnore
    @Embedded
    private AudSecret secret;

    @Embedded
    private AudLocation location;
}
