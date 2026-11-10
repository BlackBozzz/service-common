package com.argent.servicecommon.report;

/** Базовая ошибка печати отчёта на стороне сервера (ответ 500). */
public class ReportException extends RuntimeException {

    public ReportException(String message) {
        super(message);
    }

    public ReportException(String message, Throwable cause) {
        super(message, cause);
    }
}
