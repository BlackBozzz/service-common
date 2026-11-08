package com.argent.servicecommon.transfer.exp;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Таблица для экспорта без привязки к сущностям: колонки задают порядок, значения строк ищутся по {@link Column#key()};
 * значения — объекты Java или их JSON-представления, к типу колонки их приводит {@link TableExporter}.
 */
public record TableData(String title, List<Column> columns, List<Map<String, Object>> rows) {

    public TableData {
        title = title == null || title.isBlank() ? "Таблица" : title.strip();
        columns = columns == null ? List.of() : List.copyOf(columns);
        // Map.copyOf не допускает null-значений, поэтому строки только оборачиваются.
        List<Map<String, Object>> copy = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                copy.add(row == null ? Map.of() : Collections.unmodifiableMap(row));
            }
        }
        rows = Collections.unmodifiableList(copy);
    }

    /** Колонка: пустой {@code label} — используется ключ, {@code type == null} — {@link ColumnType#TEXT}. */
    public record Column(String key, String label, ColumnType type) {

        public Column {
            label = label == null || label.isBlank() ? key : label;
            type = type == null ? ColumnType.TEXT : type;
        }
    }

    /** Тип значений колонки: определяет тип ячейки XLSX и текстовый формат CSV/DOCX. */
    public enum ColumnType {
        /** Текст как есть. */
        TEXT,
        /** Число: числовая ячейка XLSX; в CSV/DOCX десятичная запятая. */
        NUMBER,
        /** Дата {@code dd.MM.yyyy}. */
        DATE,
        /** Дата и время {@code dd.MM.yyyy HH:mm}. */
        DATETIME,
        /** Логическое значение: «Да»/«Нет». */
        BOOLEAN;

        /** Разбирает тип из JSON без учёта регистра; неизвестный тип — {@link IllegalArgumentException}. */
        @JsonCreator
        public static ColumnType parse(String value) {
            if (value == null || value.isBlank()) return TEXT;
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Неизвестный тип колонки: " + value);
            }
        }
    }
}
