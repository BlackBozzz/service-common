package com.argent.servicecommon.config.validation;

import java.util.List;
import java.util.Locale;

/** Описание полного ключа настройки (включая вложенные уровни). */
public record ArgentPropertySpec(
        String key,
        String label,
        Kind kind,
        String example,
        String defaultValue,
        boolean sensitive,
        List<String> allowedValues
) {
    public enum Kind {
        TEXT, NON_BLANK, BOOLEAN, POSITIVE_INT, POSITIVE_LONG,
        MIN_500_LONG, MIN_1000_LONG, MIN_30_INT, POSITIVE_DURATION,
        EMAIL, ENUM, LIST, PATH, JDBC_URL, PORT, DATA_SIZE, LOCALE
    }

    public ArgentPropertySpec {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public String envName() {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** Варианты записи свойства. Для секретов всегда выводится только заглушка. */
    public String fillingOptions() {
        String value = sensitive ? "<секретное-значение>" : example;
        String[] parts = key.split("\\.");
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            yaml.append("  ".repeat(i)).append(parts[i]).append(":");
            yaml.append(i == parts.length - 1 ? " " + value + "\n" : "\n");
        }
        StringBuilder result = new StringBuilder()
                .append("  application.properties: ").append(key).append('=').append(value).append('\n')
                .append("  переменная окружения: ").append(envName()).append('=').append(value).append('\n')
                .append("  application.yaml:\n").append(yaml);
        if (sensitive) {
            result.append("  Docker Secret: файл /run/secrets/").append(key).append('\n')
                    .append("    + spring.config.import=optional:configtree:/run/secrets/\n")
                    .append("    (имя файла — полный ключ свойства с точками и дефисами)");
        }
        return result.toString().stripTrailing();
    }

    public String allowedDescription() {
        if (!allowedValues.isEmpty()) return "Один из вариантов: " + String.join(", ", allowedValues);
        return switch (kind) {
            case BOOLEAN -> "true или false";
            case POSITIVE_INT, POSITIVE_LONG -> "целое число больше 0";
            case MIN_500_LONG -> "целое число от 500";
            case MIN_1000_LONG -> "целое число от 1000";
            case MIN_30_INT -> "целое число от 30";
            case POSITIVE_DURATION -> "положительная длительность: 500ms, 15s, 30m, 2h, 30d либо ISO-8601 PT15M";
            case EMAIL -> "адрес email, например noreply@example.org";
            case LIST -> "список через запятую (для YAML также допустим список через -)";
            case JDBC_URL -> "JDBC URL, например jdbc:postgresql://host:5432/database или jdbc:tc:postgresql:17:///db";
            case PORT -> "порт TCP от 1 до 65535";
            case DATA_SIZE -> "размер: 20MB, 512KB, 1048576B";
            case LOCALE -> "языковой тег, например ru-RU или en-US";
            case PATH -> "путь к файлу или каталогу";
            case NON_BLANK -> "непустая строка";
            case ENUM -> "указанное значение перечисления";
            case TEXT -> "строка";
        };
    }
}
