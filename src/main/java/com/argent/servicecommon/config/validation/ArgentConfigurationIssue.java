package com.argent.servicecommon.config.validation;

/** Диагностика без раскрытия текущего значения (в том числе секретного). */
public record ArgentConfigurationIssue(
        Severity severity,
        ArgentPropertySpec property,
        String reason
) {
    public enum Severity { ERROR, WARNING }

    public String display() {
        return "[" + severity + "] " + property.key() + " — " + property.label() + "\n"
                + "Причина: " + reason + "\n"
                + "Допустимые значения: " + property.allowedDescription() + "\n"
                + "По умолчанию: " + (property.defaultValue() == null ? "не задано" : property.defaultValue()) + "\n"
                + "Как заполнить:\n" + property.fillingOptions();
    }
}
