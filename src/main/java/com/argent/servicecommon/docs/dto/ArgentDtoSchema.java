package com.argent.servicecommon.docs.dto;

import java.util.List;

/** Человекочитаемое описание DTO, включая русские подписи из @Schema и @DictionaryField. */
public record ArgentDtoSchema(String name, String javaClass, String table,
                              String title, String description, String group, List<Field> fields) {
    public ArgentDtoSchema(String name, String javaClass, String table, List<Field> fields) {
        this(name, javaClass, table, name, "", "", fields);
    }

    public record Field(String name, String title, String description, String type,
                        boolean readOnly, boolean required, String example) {
        public Field(String name, String type, boolean readOnly) {
            this(name, name, "", type, readOnly, false, "");
        }
    }
}
