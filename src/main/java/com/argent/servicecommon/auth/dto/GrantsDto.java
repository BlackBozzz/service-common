package com.argent.servicecommon.auth.dto;

import com.argent.servicecommon.security.grants.GrantOp;
import com.argent.servicecommon.security.grants.RoleGrants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Гранты в ответах REST: эффективные права пользователя или гранты одной роли.
 *
 * @param tables  операции ({@code read}, {@code write}) по именам таблиц, ключи по алфавиту;
 *                ключ {@code *} означает любую таблицу
 * @param actions коды разрешённых действий по алфавиту
 */
public record GrantsDto(Map<String, List<String>> tables, List<String> actions) {

    /** Представление грантов: таблицы и действия по алфавиту, операции строчными по алфавиту. */
    public static GrantsDto from(RoleGrants grants) {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        grants.tables().forEach((table, ops) ->
                tables.put(table, ops.stream().map(GrantOp::value).sorted().toList()));
        return new GrantsDto(tables, List.copyOf(grants.actions()));
    }
}
