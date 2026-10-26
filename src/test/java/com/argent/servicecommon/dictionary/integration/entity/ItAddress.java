package com.argent.servicecommon.dictionary.integration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Составной компонент адреса, разворачиваемый в колонки города. */
@Embeddable
public class ItAddress {
    @Column(length = 120)
    private String street;

    @Column(length = 15)
    private String postalCode;
}
