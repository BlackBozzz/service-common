package com.argent.servicecommon.audit.audfixture;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Встроенный объект без собственных {@code @AuditIgnore}: скрывается целиком аннотацией на поле сущности. */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AudSecret {

    private String code;
}
