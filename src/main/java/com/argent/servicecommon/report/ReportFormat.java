package com.argent.servicecommon.report;

import org.springframework.http.MediaType;

import java.util.Locale;

/** Формат выгрузки отчёта: расширение файла и тип содержимого HTTP-ответа. */
public enum ReportFormat {
    PDF("pdf", MediaType.APPLICATION_PDF),
    XLSX("xlsx", MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    DOCX("docx", MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    private final String extension;
    private final MediaType mediaType;

    ReportFormat(String extension, MediaType mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public MediaType mediaType() {
        return mediaType;
    }

    /** Разбирает формат без учёта регистра; неизвестное значение или {@code null} — ошибка. */
    public static ReportFormat parse(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ReportFormat format : values()) {
                if (format.extension.equals(normalized)) {
                    return format;
                }
            }
        }
        throw new IllegalArgumentException("Неизвестный формат отчёта: " + value + ". Допустимы: pdf, xlsx, docx");
    }
}
