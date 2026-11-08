package com.argent.servicecommon.transfer.imp;

import java.util.List;

/**
 * Отчёт о листе: {@code total} — непустые строки данных. У отменённого листа {@code created = updated = 0},
 * {@code failed} — строки с ошибками, остальное откатано.
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
