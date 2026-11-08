package com.argent.servicecommon.security.grants;

import com.argent.servicecommon.error.ValidationErrorsException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Разбор и запись JSON-грантов роли вида
 * {@code {"tables": {"<таблица>|*": ["read", "write"]}, "actions": ["<код>"]}}.
 *
 * <p>Разбор строгий: неизвестные ключи, недопустимые имена таблиц, операции и коды действий
 * отклоняются {@link ValidationErrorsException} с полями {@value #FIELD}, {@value #FIELD_TABLES}
 * и {@value #FIELD_ACTIONS}. Набор операций таблицы должен быть непустым.
 * Отсутствующие или {@code null}-разделы считаются пустыми.</p>
 */
public final class GrantsCodec {

    /** Поле ошибки для документа грантов целиком. */
    public static final String FIELD = "grants";

    /** Поле ошибки для раздела таблиц. */
    public static final String FIELD_TABLES = "grants.tables";

    /** Поле ошибки для раздела действий. */
    public static final String FIELD_ACTIONS = "grants.actions";

    /** Ключ, означающий любую таблицу. */
    public static final String ANY_TABLE = "*";

    private static final String TABLES = "tables";
    private static final String ACTIONS = "actions";

    private static final Pattern TABLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern ACTION_CODE = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_-]+)*");

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private GrantsCodec() {
    }

    /**
     * Разбирает и проверяет JSON-гранты; {@code null} или пустая строка — гранты без прав.
     *
     * @throws ValidationErrorsException если документ некорректен
     */
    public static RoleGrants parse(String json) {
        if (json == null || json.isBlank()) {
            return RoleGrants.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException exception) {
            throw formatError(FIELD);
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return RoleGrants.empty();
        }
        if (!root.isObject()) {
            throw formatError(FIELD);
        }

        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : root.properties()) {
            String key = property.getKey();
            if (!TABLES.equals(key) && !ACTIONS.equals(key)) {
                errors.putIfAbsent(FIELD, "Неизвестное поле грантов: " + key);
            }
        }
        Map<String, Set<GrantOp>> tables = parseTables(root.get(TABLES), errors);
        Set<String> actions = parseActions(root.get(ACTIONS), errors);
        if (!errors.isEmpty()) {
            throw new ValidationErrorsException(errors);
        }
        return new RoleGrants(tables, actions);
    }

    /** Записывает гранты в JSON с упорядоченными таблицами, операциями и действиями. */
    public static String write(RoleGrants grants) {
        RoleGrants value = grants == null ? RoleGrants.empty() : grants;
        Map<String, Object> document = new LinkedHashMap<>();
        Map<String, List<String>> tables = new LinkedHashMap<>();
        value.tables().forEach((table, ops) ->
                tables.put(table, ops.stream().map(GrantOp::value).toList()));
        document.put(TABLES, tables);
        document.put(ACTIONS, List.copyOf(value.actions()));
        return MAPPER.writeValueAsString(document);
    }

    private static Map<String, Set<GrantOp>> parseTables(JsonNode node, Map<String, String> errors) {
        Map<String, Set<GrantOp>> tables = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tables;
        }
        if (!node.isObject()) {
            errors.putIfAbsent(FIELD_TABLES, formatMessage());
            return tables;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String table = entry.getKey();
            if (!ANY_TABLE.equals(table) && !TABLE_NAME.matcher(table).matches()) {
                errors.putIfAbsent(FIELD_TABLES, "Недопустимое имя таблицы: " + table);
                continue;
            }
            JsonNode opsNode = entry.getValue();
            if (!opsNode.isArray()) {
                errors.putIfAbsent(FIELD_TABLES, formatMessage());
                continue;
            }
            if (opsNode.isEmpty()) {
                errors.putIfAbsent(FIELD_TABLES, "Пустой набор операций для таблицы: " + table);
                continue;
            }
            Set<GrantOp> ops = EnumSet.noneOf(GrantOp.class);
            for (JsonNode opNode : opsNode) {
                String raw = opNode.isString() ? opNode.stringValue() : opNode.toString();
                GrantOp.fromValue(opNode.isString() ? raw : null).ifPresentOrElse(ops::add,
                        () -> errors.putIfAbsent(FIELD_TABLES, "Недопустимая операция: " + raw));
            }
            tables.put(table, ops);
        }
        return tables;
    }

    private static Set<String> parseActions(JsonNode node, Map<String, String> errors) {
        Set<String> actions = new LinkedHashSet<>();
        if (node == null || node.isNull()) {
            return actions;
        }
        if (!node.isArray()) {
            errors.putIfAbsent(FIELD_ACTIONS, formatMessage());
            return actions;
        }
        for (JsonNode actionNode : node) {
            String raw = actionNode.isString() ? actionNode.stringValue() : actionNode.toString();
            if (actionNode.isString() && ACTION_CODE.matcher(raw).matches()) {
                actions.add(raw);
            } else {
                errors.putIfAbsent(FIELD_ACTIONS, "Недопустимый код действия: " + raw);
            }
        }
        return actions;
    }

    private static ValidationErrorsException formatError(String field) {
        return new ValidationErrorsException(Map.of(field, formatMessage()));
    }

    private static String formatMessage() {
        return "Недопустимый формат грантов";
    }
}
