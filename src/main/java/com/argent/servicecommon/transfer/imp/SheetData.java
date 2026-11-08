package com.argent.servicecommon.transfer.imp;

import java.util.List;

/**
 * Прочитанный лист таблицы: имя, заголовки первой строки и непустые строки данных.
 *
 * @param name    имя листа XLSX; для CSV — {@code CSV}
 * @param headers заголовки колонок в порядке файла
 * @param rows    строки данных с номерами строк Excel
 */
public record SheetData(String name, List<String> headers, List<RowData> rows) {

    public SheetData {
        headers = headers == null ? List.of() : List.copyOf(headers);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
