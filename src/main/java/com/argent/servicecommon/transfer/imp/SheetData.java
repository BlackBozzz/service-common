package com.argent.servicecommon.transfer.imp;

import java.util.List;

/** Прочитанный лист: имя (для CSV — {@code CSV}), заголовки и непустые строки данных. */
public record SheetData(String name, List<String> headers, List<RowData> rows) {

    public SheetData {
        headers = headers == null ? List.of() : List.copyOf(headers);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
