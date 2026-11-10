package com.argent.servicecommon.report;

/** Некорректный запрос на печать отчёта от клиента (ответ 400). */
public class ReportParameterException extends IllegalArgumentException {

    public ReportParameterException(String message) {
        super(message);
    }
}
