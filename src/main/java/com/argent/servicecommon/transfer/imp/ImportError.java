package com.argent.servicecommon.transfer.imp;

/** Ошибка импорта: {@code row} — строка Excel ({@code 0} — ошибка листа), {@code field == null} — вся строка. */
public record ImportError(int row, String field, String message) {
}
