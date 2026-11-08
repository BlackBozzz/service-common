package com.argent.servicecommon.transfer.imp;

/** Результат листа многолистового импорта; {@code dictionary == null} — справочник для листа не найден. */
public record SheetImportResult(String sheet, String dictionary, ImportReport report) {
}
