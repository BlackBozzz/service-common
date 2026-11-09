package com.argent.servicecommon.audit.audfixture;

import com.argent.servicecommon.audit.Audited;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** {@code @Table(name)} в CamelCase: стратегия именования даёт физическую таблицу {@code aud_upper}. */
@Entity
@Audited
@Table(name = "AudUpper")
@Getter
@Setter
public class AudUpper {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;
}
