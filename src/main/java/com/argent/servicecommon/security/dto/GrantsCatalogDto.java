package com.argent.servicecommon.security.dto;

import java.util.List;

/**
 * Каталог подсказок для редактора грантов.
 *
 * @param tables  коды зарегистрированных справочников по алфавиту
 * @param actions действия приложения из {@code argent.security.actions} и встроенное {@code admin} по алфавиту
 */
public record GrantsCatalogDto(List<String> tables, List<String> actions) {
}
