package com.argent.servicecommon.security.grants;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Гранты ↔ authorities: {@code table:<t>:<op>} (ключ {@code *} сохраняется буквально) и {@code action:<a>}. */
public final class GrantsAuthorities {

    /** Префикс authority таблицы. */
    public static final String TABLE_PREFIX = "table:";

    /** Префикс authority действия. */
    public static final String ACTION_PREFIX = "action:";

    private GrantsAuthorities() {
    }

    /** Authority операции над таблицей: {@code table:<t>:<op>}. */
    public static String tableAuthority(String table, GrantOp op) {
        return TABLE_PREFIX + table + ":" + op.value();
    }

    /** Authority действия: {@code action:<a>}. */
    public static String actionAuthority(String code) {
        return ACTION_PREFIX + code;
    }

    /** Authorities, соответствующие грантам. */
    public static Set<String> toAuthorities(RoleGrants grants) {
        Set<String> authorities = new LinkedHashSet<>();
        grants.tables().forEach((table, ops) ->
                ops.forEach(op -> authorities.add(tableAuthority(table, op))));
        grants.actions().forEach(action -> authorities.add(actionAuthority(action)));
        return Set.copyOf(authorities);
    }

    /** Объединение грантов нескольких ролей: операции по таблицам и действия складываются. */
    public static RoleGrants merge(Collection<RoleGrants> grants) {
        Map<String, Set<GrantOp>> tables = new HashMap<>();
        Set<String> actions = new HashSet<>();
        for (RoleGrants item : grants) {
            item.tables().forEach((table, ops) ->
                    tables.computeIfAbsent(table, key -> EnumSet.noneOf(GrantOp.class)).addAll(ops));
            actions.addAll(item.actions());
        }
        return new RoleGrants(tables, actions);
    }

    /**
     * Покрываются ли гранты {@code wanted} правами {@code holder}: каждая запрошенная операция над таблицей
     * разрешена держателю для этой таблицы или через {@code *} (запрошенный {@code *} требует {@code *}
     * у держателя), и каждое запрошенное действие есть у держателя.
     *
     * <p>Встроенное действие {@value GrantConstants#ADMIN_ACTION} покрывает любые действия: держателю
     * {@code admin} проверка действий всегда проходит. Проверка таблиц от этого не меняется.</p>
     */
    public static boolean covers(RoleGrants holder, RoleGrants wanted) {
        Set<GrantOp> anyTable = holder.tables().getOrDefault(GrantsCodec.ANY_TABLE, Set.of());
        for (Map.Entry<String, Set<GrantOp>> entry : wanted.tables().entrySet()) {
            Set<GrantOp> held = holder.tables().getOrDefault(entry.getKey(), Set.of());
            for (GrantOp op : entry.getValue()) {
                if (!held.contains(op) && !anyTable.contains(op)) {
                    return false;
                }
            }
        }
        return holder.actions().contains(GrantConstants.ADMIN_ACTION)
                || holder.actions().containsAll(wanted.actions());
    }

    /**
     * Гранты из {@code updated}, которых нет в {@code previous}: операции, отсутствующие у той же таблицы
     * (ключи сравниваются буквально, {@code *} не раскрывается), и новые действия.
     */
    public static RoleGrants added(RoleGrants previous, RoleGrants updated) {
        Map<String, Set<GrantOp>> tables = new HashMap<>();
        updated.tables().forEach((table, ops) -> {
            Set<GrantOp> missing = EnumSet.noneOf(GrantOp.class);
            missing.addAll(ops);
            missing.removeAll(previous.tables().getOrDefault(table, Set.of()));
            if (!missing.isEmpty()) {
                tables.put(table, missing);
            }
        });
        Set<String> actions = new HashSet<>(updated.actions());
        actions.removeAll(previous.actions());
        return new RoleGrants(tables, actions);
    }

    /**
     * Есть ли в наборе authorities действие {@code code}.
     *
     * <p>Встроенное действие {@value GrantConstants#ADMIN_ACTION} — суперпользовательское: authority
     * {@code action:admin} проходит проверку любого действия. Для {@code null} — {@code false}.</p>
     */
    public static boolean hasAction(Collection<? extends GrantedAuthority> authorities, String code) {
        if (code == null) {
            return false;
        }
        String wanted = actionAuthority(code);
        String admin = actionAuthority(GrantConstants.ADMIN_ACTION);
        for (GrantedAuthority authority : authorities) {
            String value = authority.getAuthority();
            if (wanted.equals(value) || admin.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Разрешена ли операция над таблицей набором authorities, с учётом {@code table:*:<op>}. */
    public static boolean allowsTable(Collection<? extends GrantedAuthority> authorities, String table, GrantOp op) {
        if (table == null || op == null) {
            return false;
        }
        String exact = tableAuthority(table, op);
        String any = tableAuthority(GrantsCodec.ANY_TABLE, op);
        for (GrantedAuthority authority : authorities) {
            String value = authority.getAuthority();
            if (exact.equals(value) || any.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
