package com.argent.servicecommon.audit.audfixture;

import com.argent.servicecommon.audit.Audited;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Аудируемая сущность со строковым ключом произвольной длины. */
@Entity
@Audited
@Table(name = "aud_long_id")
@Getter
@Setter
public class AudLongId {

    @Id
    private String id;

    private String name;
}
