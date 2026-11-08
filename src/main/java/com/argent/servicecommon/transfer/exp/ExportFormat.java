package com.argent.servicecommon.transfer.exp;

import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Формат файла экспорта таблицы: расширение и тип содержимого ответа. */
public enum ExportFormat {
    XLSX("xlsx", MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    CSV("csv", new MediaType("text", "csv", StandardCharsets.UTF_8)),
    DOCX("docx", MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    /** Сообщение для запроса PDF: формат не поддерживается. */
    public static final String PDF_HINT = "Формат PDF не поддерживается";

    private final String extension;
    private final MediaType mediaType;

    ExportFormat(String extension, MediaType mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public MediaType mediaType() {
        return mediaType;
    }

    /**
     * Разбирает формат без учёта регистра.
     *
     * @throws IllegalArgumentException для {@code pdf} — {@link #PDF_HINT}, для прочих —
     *                                  «Неизвестный формат экспорта: …»
     */
    public static ExportFormat parse(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("pdf")) {
            throw new IllegalArgumentException(PDF_HINT);
        }
        for (ExportFormat format : values()) {
            if (format.extension.equals(normalized)) return format;
        }
        throw new IllegalArgumentException("Неизвестный формат экспорта: " + value);
    }
}
