package com.argent.servicecommon.audit.audfixture;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Точка — встроенный объект внутри {@link AudLocation}. */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AudPoint {

    private Double lat;

    private Double lon;
}
