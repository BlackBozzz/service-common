package com.argent.servicecommon.dictionary.crud.crudfixture;

import jakarta.persistence.*;

/** Сущность без регистрации, используемая для проверки составного ключа. */
@Entity
@Table(name = "crud_composite")
@IdClass(CrudCompositeKey.class)
public class CrudComposite {
    @Id
    private Long partNumber;

    @Id
    private String partCode;

    public Long getPartNumber() { return partNumber; }
    public void setPartNumber(Long partNumber) { this.partNumber = partNumber; }
    public String getPartCode() { return partCode; }
    public void setPartCode(String partCode) { this.partCode = partCode; }
}
