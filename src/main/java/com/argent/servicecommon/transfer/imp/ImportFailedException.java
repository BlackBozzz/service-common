package com.argent.servicecommon.transfer.imp;

import java.util.Objects;

/**
 * Импорт листа отменён и откатан; отчёт содержит все найденные ошибки.
 *
 * <p>REST отвечает 400 ProblemDetail с заголовком «Ошибка импорта» и отчётом в свойстве {@code report}.</p>
 */
public class ImportFailedException extends RuntimeException {

    private final transient ImportReport report;

    public ImportFailedException(ImportReport report) {
        super("Импорт отменён: в данных есть ошибки");
        this.report = Objects.requireNonNull(report);
    }

    /** Отчёт об отменённом импорте. */
    public ImportReport report() {
        return report;
    }
}
