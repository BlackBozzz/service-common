package com.argent.servicecommon.dictionary.crud.crudfixture;

import java.io.Serializable;
import java.util.Objects;

/** Составной ключ тестовой сущности. */
public class CrudCompositeKey implements Serializable {
    private static final long serialVersionUID = 1L;

    public Long partNumber;
    public String partCode;

    public CrudCompositeKey() { }

    public CrudCompositeKey(Long partNumber, String partCode) {
        this.partNumber = partNumber;
        this.partCode = partCode;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CrudCompositeKey key)) return false;
        return Objects.equals(partNumber, key.partNumber)
                && Objects.equals(partCode, key.partCode);
    }

    @Override
    public int hashCode() { return Objects.hash(partNumber, partCode); }
}
