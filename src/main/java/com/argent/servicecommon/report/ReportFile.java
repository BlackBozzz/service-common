package com.argent.servicecommon.report;

import com.argent.servicecommon.util.FileNames;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** Готовый файл отчёта; {@code fileName} уже содержит расширение. */
public record ReportFile(byte[] content, ReportFormat format, String fileName) {

    /** Собирает файл, очищая базовое имя и добавляя расширение формата. */
    public static ReportFile of(byte[] content, ReportFormat format, String baseName) {
        return new ReportFile(content, format, FileNames.sanitize(baseName, "report") + "." + format.extension());
    }

    /** Ответ для скачивания: attachment, {@code nosniff}, тип и длина содержимого. */
    public ResponseEntity<byte[]> toResponseEntity() {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(format.mediaType())
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }
}
