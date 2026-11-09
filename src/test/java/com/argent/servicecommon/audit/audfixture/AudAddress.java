package com.argent.servicecommon.audit.audfixture;

import com.argent.servicecommon.audit.AuditIgnore;
import jakarta.persistence.Embeddable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Встроенный адрес с секретным подполем и ссылкой на регион; {@code toString()} от Lombok выдал бы секрет целиком.
 */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AudAddress {

    private String city;

    @AuditIgnore
    private String pin;

    @ManyToOne
    @JoinColumn(name = "region_id")
    private AudRegion region;

    public AudAddress(String city, String pin) {
        this(city, pin, null);
    }
}
