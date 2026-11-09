package com.argent.servicecommon.audit.audfixture;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Встроенный объект с вложенным встроенным {@link AudPoint}: в журнале — {@code location.point.lat}. */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AudLocation {

    private String label;

    @Embedded
    private AudPoint point;
}
