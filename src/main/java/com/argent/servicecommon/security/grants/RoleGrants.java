package com.argent.servicecommon.security.grants;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Гранты роли: операции по таблицам и коды действий.
 *
 * <p>Неизменяемое значение: коллекции копируются и оборачиваются в неизменяемые,
 * {@code null} заменяется пустой коллекцией. Ключ {@code *} в {@link #tables} означает любую таблицу.</p>
 *
 * @param tables  операции по именам таблиц (или {@code *})
 * @param actions коды разрешённых действий
 */
public record RoleGrants(Map<String, Set<GrantOp>> tables, Set<String> actions) {

    private static final RoleGrants EMPTY = new RoleGrants(Map.of(), Set.of());

    public RoleGrants {
        TreeMap<String, Set<GrantOp>> tablesCopy = new TreeMap<>();
        if (tables != null) {
            tables.forEach((table, ops) -> {
                EnumSet<GrantOp> opsCopy = EnumSet.noneOf(GrantOp.class);
                if (ops != null) {
                    opsCopy.addAll(ops);
                }
                tablesCopy.put(table, Collections.unmodifiableSet(opsCopy));
            });
        }
        tables = Collections.unmodifiableMap(tablesCopy);
        actions = actions == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(actions));
    }

    /** Гранты без прав. */
    public static RoleGrants empty() {
        return EMPTY;
    }
}
