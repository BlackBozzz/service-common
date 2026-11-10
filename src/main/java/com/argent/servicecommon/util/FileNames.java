package com.argent.servicecommon.util;

/** Общая очистка имён файлов для скачиваемых выгрузок (экспорт, отчёты). */
public final class FileNames {

    private FileNames() {
    }

    /**
     * Имя файла без символов, запрещённых в именах файлов, не длиннее 100 символов;
     * для пустого имени или одних точек возвращает {@code fallback}.
     */
    public static String sanitize(String title, String fallback) {
        String cleaned = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (cleaned.length() > 100) {
            int end = Character.isHighSurrogate(cleaned.charAt(99)) ? 99 : 100;
            cleaned = cleaned.substring(0, end).strip();
        }
        return cleaned.isEmpty() || cleaned.chars().allMatch(ch -> ch == '.') ? fallback : cleaned;
    }
}
