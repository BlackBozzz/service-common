package com.argent.servicecommon.security.grants;

import com.argent.servicecommon.security.CurrentUser;

/** Проверки прав для SpEL, бин {@code argentAccess}: {@code action(code)} и {@code table(table, op)}. */
public class ArgentAccess {

    /**
     * Есть ли у текущего пользователя действие {@code code}; держатель встроенного действия
     * {@code admin} проходит проверку любого действия.
     */
    public boolean action(String code) {
        return CurrentUser.hasAction(code);
    }

    /**
     * Разрешена ли текущему пользователю операция над таблицей с учётом ключа {@code *}.
     *
     * @param op {@code read} или {@code write} без учёта регистра; неизвестная операция — {@code false}
     */
    public boolean table(String table, String op) {
        return CurrentUser.canTable(table, op);
    }
}
