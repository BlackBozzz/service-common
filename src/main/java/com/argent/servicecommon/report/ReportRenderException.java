package com.argent.servicecommon.report;

/** Ошибка заполнения или выгрузки отчёта. */
public class ReportRenderException extends ReportException {

    public ReportRenderException(String message) {
        super(message);
    }

    public ReportRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
