package com.argent.servicecommon.transfer.imp;

import java.util.List;

/**
 * Отчёт об импорте листа.
 *
 * <p>Если импорт листа отменён (стратегия {@code fail-all} или ошибка базы данных), {@code created} и
 * {@code updated} равны нулю, а {@code failed} — числу строк с ошибками; остальные строки откатаны.</p>
 *
 * @param total   число непустых строк данных
 * @param created создано записей
 * @param updated обновлено записей
 * @param failed  строк с ошибками
 * @param errors  ошибки по строкам в порядке файла
 */
public record ImportReport(int total, int created, int updated, int failed, List<ImportError> errors) {

    public ImportReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Отчёт о листе, который не импортирован целиком по одной причине. */
    public static ImportReport rejected(int total, String message) {
        return new ImportReport(total, 0, 0, total, List.of(new ImportError(0, null, message)));
    }
}
