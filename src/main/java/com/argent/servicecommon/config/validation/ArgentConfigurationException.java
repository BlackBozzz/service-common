package com.argent.servicecommon.config.validation;

import java.util.List;

/** Останавливает запуск с полными путями и вариантами заполнения каждого ошибочного свойства. */
public class ArgentConfigurationException extends IllegalStateException {
    private final List<ArgentConfigurationIssue> issues;

    public ArgentConfigurationException(List<ArgentConfigurationIssue> issues) {
        super("Ошибки конфигурации AtomSkills:\n\n"
                + issues.stream().map(ArgentConfigurationIssue::display)
                .reduce((a, b) -> a + "\n\n" + b).orElse("Неизвестная ошибка"));
        this.issues = List.copyOf(issues);
    }

    public List<ArgentConfigurationIssue> issues() {
        return issues;
    }
}
