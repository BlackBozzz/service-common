package com.argent.servicecommon.dictionary.dynamic.dto;

/** Элемент общего каталога: сгенерированный справочник или таблица приложения. */
public record DynamicDictionarySummary(String code, String name, String kind, String group) {
    /** Совместимость с тестами/потребителями API прежней версии. */
    public DynamicDictionarySummary(String code, String name) {
        this(code, name, "dictionary", "Справочники");
    }
}
