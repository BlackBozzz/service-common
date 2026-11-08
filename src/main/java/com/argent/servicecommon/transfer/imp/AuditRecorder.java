package com.argent.servicecommon.transfer.imp;

/** Запись массовых операций импорта в аудит; по умолчанию без действий, модуль аудита заменяет бин. */
public interface AuditRecorder {

    /** Фиксирует REPLACE справочника {@code table} с числом удалённых строк. */
    void recordReplace(String table, int deletedRows);
}
