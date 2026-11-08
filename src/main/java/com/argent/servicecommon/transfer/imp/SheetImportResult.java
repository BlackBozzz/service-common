package com.argent.servicecommon.transfer.imp;

/**
 * Результат импорта одного листа многолистового файла.
 *
 * @param sheet      имя листа
 * @param dictionary код справочника или {@code null}, если справочник для листа не найден
 * @param report     отчёт по листу
 */
public record SheetImportResult(String sheet, String dictionary, ImportReport report) {
}
