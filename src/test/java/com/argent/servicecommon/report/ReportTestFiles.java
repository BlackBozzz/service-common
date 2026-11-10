package com.argent.servicecommon.report;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Чтение содержимого выгруженных отчётов в тестах. */
final class ReportTestFiles {

    private ReportTestFiles() {
    }

    /** Текст записи ZIP-архива (XLSX/DOCX) в UTF-8. */
    static String zipEntryText(byte[] zip, String entry) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry current = in.getNextEntry(); current != null; current = in.getNextEntry()) {
                if (current.getName().equals(entry)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("В архиве нет записи " + entry);
    }
}
