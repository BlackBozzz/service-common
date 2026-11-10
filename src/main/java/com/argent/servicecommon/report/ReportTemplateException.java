package com.argent.servicecommon.report;

/** Шаблон отчёта не найден или не компилируется. */
public class ReportTemplateException extends ReportException {

    public ReportTemplateException(String message) {
        super(message);
    }

    public ReportTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
