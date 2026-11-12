package com.argent.servicecommon.mail;

/** Результат синхронной отправки: selected = sent + skipped + failed. */
public record ArgentMailResult(int selected, int sent, int skipped, int failed) {
}
