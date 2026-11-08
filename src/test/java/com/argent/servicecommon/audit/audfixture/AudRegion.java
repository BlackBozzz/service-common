package com.argent.servicecommon.audit.audfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Тестовый регион, на который ссылается {@link AudOrder}; сам не аудируется. */
@Entity
@Table(name = "aud_region")
@Getter
@Setter
public class AudRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
}
