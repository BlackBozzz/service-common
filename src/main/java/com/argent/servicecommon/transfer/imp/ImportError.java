package com.argent.servicecommon.transfer.imp;

/**
 * Ошибка импорта.
 *
 * @param row     номер строки Excel; {@code 0} — ошибка листа (например, несопоставленная колонка)
 * @param field   имя поля справочника или {@code null}, если ошибка относится ко всей строке
 * @param message сообщение для пользователя
 */
public record ImportError(int row, String field, String message) {
}
