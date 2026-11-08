package com.argent.servicecommon.security.grants;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Optional;

/**
 * Операция над таблицей в грантах роли. В JSON записывается строчным значением.
 *
 * <p>{@link #WRITE} не подразумевает {@link #READ}: каждая операция выдаётся отдельно.</p>
 */
public enum GrantOp {

    /** Чтение записей и схемы. */
    READ("read"),

    /** Создание, изменение и удаление записей. */
    WRITE("write");

    private final String value;

    GrantOp(String value) {
        this.value = value;
    }

    /** Строчное значение операции, как в JSON и в authority {@code table:<t>:<op>}. */
    @JsonValue
    public String value() {
        return value;
    }

    /** Операция по точному строчному значению или пустое значение, если такой нет. */
    public static Optional<GrantOp> fromValue(String value) {
        for (GrantOp op : values()) {
            if (op.value.equals(value)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }

    /** Операция по значению без учёта регистра или пустое значение, если такой нет. */
    public static Optional<GrantOp> fromValueIgnoreCase(String value) {
        return value == null ? Optional.empty() : fromValue(value.toLowerCase(Locale.ROOT));
    }
}
