package com.argent.servicecommon.docs.dto;

import java.util.List;

/** Метаданные структуры БД, без чтения содержимого таблиц. */
public record ArgentDbSchema(String schema, List<Table> tables) {
    public record Table(String name, String title, String description, String group,
                        List<Column> columns, List<Relation> relations) {
        public Table(String name, List<Column> columns, List<Relation> relations) {
            this(name, name, "", "Другие таблицы", columns, relations);
        }
    }
    public record Column(String name, String title, String description, String type,
                         boolean nullable, boolean primaryKey) {
        public Column(String name, String type, boolean nullable, boolean primaryKey) {
            this(name, name, "", type, nullable, primaryKey);
        }
    }
    public record Relation(String fromColumn, String toTable, String toColumn) { }
}
