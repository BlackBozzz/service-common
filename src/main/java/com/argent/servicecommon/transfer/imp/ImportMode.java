package com.argent.servicecommon.transfer.imp;

import java.util.Locale;

/** Режим импорта листа в справочник. */
public enum ImportMode {
    /** Удалить все записи справочника, затем создать записи из листа. */
    REPLACE,
    /** Найти запись по ключевым полям: обновить найденную, иначе создать. */
    UPSERT;

    /** Разбирает режим без учёта регистра; неизвестное значение — {@link IllegalArgumentException}. */
    public static ImportMode parse(String value) {
        if (value != null) {
            for (ImportMode mode : values()) {
                if (mode.name().equals(value.trim().toUpperCase(Locale.ROOT))) {
                    return mode;
                }
            }
        }
        throw new IllegalArgumentException("Режим импорта должен быть REPLACE или UPSERT");
    }
}
