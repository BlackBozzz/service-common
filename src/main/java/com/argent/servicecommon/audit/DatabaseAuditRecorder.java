package com.argent.servicecommon.audit;

import com.argent.servicecommon.transfer.imp.AuditRecorder;

import java.util.Objects;

/**
 * Запись в журнал аудита массовой замены справочника при импорте в режиме REPLACE: одна строка с
 * действием {@code REPLACE}, без идентификатора записи, с числом удалённых строк в {@code new_value}.
 */
public class DatabaseAuditRecorder implements AuditRecorder {

    /** Действие журнала для замены содержимого таблицы. */
    public static final String REPLACE_ACTION = "REPLACE";

    private final AuditWriter writer;

    public DatabaseAuditRecorder(AuditWriter writer) {
        this.writer = Objects.requireNonNull(writer);
    }

    @Override
    public void recordReplace(String table, int deletedRows) {
        writer.write(table, null, REPLACE_ACTION, null, null, String.valueOf(deletedRows));
    }
}
