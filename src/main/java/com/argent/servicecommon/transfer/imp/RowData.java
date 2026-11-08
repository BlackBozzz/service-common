package com.argent.servicecommon.transfer.imp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Строка листа: номер строки Excel (заголовок — 1, CSV — номер записи) и значения; пустая ячейка — {@code ""}. */
public record RowData(int excelRow, Map<String, String> values) {

    public RowData {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Значение по заголовку или {@code null}, если такой колонки нет. */
    public String value(String header) {
        return values.get(header);
    }

    /** Все ячейки строки пустые. */
    public boolean blank() {
        return values.values().stream().allMatch(value -> value == null || value.isBlank());
    }
}
