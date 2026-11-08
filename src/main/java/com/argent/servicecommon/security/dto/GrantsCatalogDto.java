package com.argent.servicecommon.security.dto;

import java.util.List;

/**
 * Подсказки редактора грантов по алфавиту: коды справочников и действия ({@code argent.security.actions}, {@code
 * admin}).
 */
public record GrantsCatalogDto(List<String> tables, List<String> actions) {
}
