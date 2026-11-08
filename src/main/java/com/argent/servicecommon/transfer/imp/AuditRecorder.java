package com.argent.servicecommon.transfer.imp;

/**
 * Запись в аудит массовых операций импорта.
 *
 * <p>По умолчанию подключается реализация без действий; модуль аудита заменяет её своим бином.</p>
 */
public interface AuditRecorder {

    /**
     * Фиксирует замену содержимого справочника в режиме REPLACE.
     *
     * @param table       код справочника (имя таблицы)
     * @param deletedRows число удалённых строк
     */
    void recordReplace(String table, int deletedRows);
}
