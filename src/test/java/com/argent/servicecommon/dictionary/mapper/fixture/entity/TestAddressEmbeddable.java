package com.argent.servicecommon.dictionary.mapper.fixture.entity;

import jakarta.persistence.Embeddable;

/** Встраиваемый тестовый адрес. */
@Embeddable
public class TestAddressEmbeddable {
    private String street;
    private String postalCode;
    private String suite;
    public String getSuite() { return suite; }
    public void setSuite(String suite) { this.suite = suite; }
    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
}
