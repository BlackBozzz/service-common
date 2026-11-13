package com.argent.servicecommon.config.validation;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка настроек ДО создания сервисов и подключения к БД.
 * Не печатает реальные значения настроек — включая секреты, URL с паролем и токены.
 */
public final class ArgentConfigurationInspector {
    private static final Pattern DURATION = Pattern.compile("^([0-9]+)(ns|us|ms|s|m|h|d)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile("^[1-9][0-9]*(B|KB|MB|GB|TB)?$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BUILTIN_PREFIXES = Set.of(
            "ARGENT_AUTH_", "ARGENT_SECURITY_", "ARGENT_QUEUE_", "ARGENT_MAIL_",
            "ARGENT_ATTACHMENTS_", "ARGENT_REPORTS_", "ARGENT_TRANSFER_",
            "ARGENT_LIVE_", "ARGENT_DOCS_", "ARGENT_CONFIG_VALIDATION_");

    private ArgentConfigurationInspector() {}

    /** Вернуть все проблемы без остановки приложения. */
    public static List<ArgentConfigurationIssue> inspect(Environment env) {
        List<ArgentConfigurationIssue> issues = new ArrayList<>();
        for (ArgentPropertySpec property : ArgentConfigurationCatalog.all()) {
            String value = env.getProperty(property.key());
            if (value != null && !valid(property, value)) {
                issues.add(new ArgentConfigurationIssue(
                        ArgentConfigurationIssue.Severity.ERROR, property,
                        "Указанное значение имеет неподходящий формат или выходит за допустимые пределы."));
            }
        }
        boolean mail = isTrue(env, "argent.mail.enabled");
        if (mail) {
            require(issues, env, "spring.mail.host", "Почтовый модуль включён (argent.mail.enabled=true).");
            require(issues, env, "argent.mail.from", "Почтовый модуль включён (argent.mail.enabled=true).");
            if (isTrue(env, "spring.mail.properties.mail.smtp.auth")) {
                require(issues, env, "spring.mail.username", "Включена SMTP-аутентификация.");
                require(issues, env, "spring.mail.password", "Включена SMTP-аутентификация.");
            }
            if (isFalse(env, "argent.queue.enabled")) {
                issues.add(new ArgentConfigurationIssue(ArgentConfigurationIssue.Severity.WARNING,
                        ArgentConfigurationCatalog.byKey().get("argent.queue.enabled"),
                        "Очередь отключена: синхронная почта работает, но queueTo* не будет выполняться."));
            }
        }
        if (isFalse(env, "argent.auth.password-required") && !isFalse(env, "argent.security.enabled")) {
            issues.add(new ArgentConfigurationIssue(ArgentConfigurationIssue.Severity.WARNING,
                    ArgentConfigurationCatalog.byKey().get("argent.auth.password-required"),
                    "Вход без пароля допустим только в изолированном демонстрационном окружении."));
        }
        checkUnknown(env, issues);
        return List.copyOf(issues);
    }

    /** Прервать запуск, если есть ошибки; предупреждения не блокируют приложение. */
    public static void validateOrThrow(Environment env) {
        List<ArgentConfigurationIssue> errors = inspect(env).stream()
                .filter(issue -> issue.severity() == ArgentConfigurationIssue.Severity.ERROR).toList();
        if (!errors.isEmpty()) throw new ArgentConfigurationException(errors);
    }

    /** Сформировать человекочитаемый отчёт без раскрытия секретов. */
    public static String describeIssues(List<ArgentConfigurationIssue> issues) {
        if (issues.isEmpty()) return "Настройки AtomSkills: ошибок не обнаружено.";
        return issues.stream().map(ArgentConfigurationIssue::display)
                .reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    private static void require(List<ArgentConfigurationIssue> issues, Environment env, String key, String why) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) {
            issues.add(new ArgentConfigurationIssue(ArgentConfigurationIssue.Severity.ERROR,
                    ArgentConfigurationCatalog.byKey().get(key),
                    "Обязательное свойство отсутствует или пустое. " + why));
        }
    }

    private static boolean isTrue(Environment env, String key) {
        return "true".equalsIgnoreCase(String.valueOf(env.getProperty(key)));
    }

    private static boolean isFalse(Environment env, String key) {
        return "false".equalsIgnoreCase(String.valueOf(env.getProperty(key)));
    }

    private static boolean valid(ArgentPropertySpec spec, String raw) {
        String value = raw.trim();
        try {
            return switch (spec.kind()) {
                case TEXT, LIST -> true;
                case NON_BLANK, PATH -> !value.isBlank();
                case BOOLEAN -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
                case POSITIVE_INT -> Integer.parseInt(value) > 0;
                case POSITIVE_LONG -> Long.parseLong(value) > 0;
                case MIN_500_LONG -> Long.parseLong(value) >= 500;
                case MIN_1000_LONG -> Long.parseLong(value) >= 1000;
                case MIN_30_INT -> Integer.parseInt(value) >= 30;
                case POSITIVE_DURATION -> positiveDuration(value);
                case EMAIL -> !value.isBlank() && value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
                case ENUM -> spec.allowedValues().stream().anyMatch(allowed ->
                        allowed.replace('-', '_').equalsIgnoreCase(value.replace('-', '_')));
                case JDBC_URL -> value.startsWith("jdbc:") && value.length() > "jdbc:".length();
                case PORT -> Integer.parseInt(value) >= 1 && Integer.parseInt(value) <= 65535;
                case DATA_SIZE -> SIZE.matcher(value).matches();
                case LOCALE -> !value.isBlank() && !Locale.forLanguageTag(value).getLanguage().isBlank();
            };
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean positiveDuration(String text) {
        Matcher matcher = DURATION.matcher(text);
        if (matcher.matches()) return Long.parseLong(matcher.group(1)) > 0;
        return !Duration.parse(text.toUpperCase(Locale.ROOT)).isNegative()
                && !Duration.parse(text.toUpperCase(Locale.ROOT)).isZero();
    }

    private static void checkUnknown(Environment env, List<ArgentConfigurationIssue> issues) {
        if (!(env instanceof ConfigurableEnvironment configurable)) return;
        String mode = env.getProperty("argent.config-validation.unknown-keys", "warn").toLowerCase(Locale.ROOT);
        if ("ignore".equals(mode) || !("warn".equals(mode) || "error".equals(mode))) return;
        Map<String, ArgentPropertySpec> known = new LinkedHashMap<>();
        for (ArgentPropertySpec spec : ArgentConfigurationCatalog.all()) {
            if (spec.key().startsWith("argent.")) known.put(canonical(spec.key()), spec);
        }
        Set<String> checked = new HashSet<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                String canonical = canonical(name);
                if (!BUILTIN_PREFIXES.stream().anyMatch(canonical::startsWith)) continue;
                // Числовой суффикс — элемент списка в YAML / env (actions[0], ACTIONS_0).
                String base = canonical.replaceFirst("_[0-9]+$", "");
                if (known.containsKey(canonical) || known.containsKey(base) || !checked.add(canonical)) continue;
                ArgentPropertySpec nearest = nearest(known, canonical);
                String label = nearest == null ? "Неизвестное свойство" : "Неизвестное свойство (возможно, опечатка)";
                ArgentPropertySpec unknown = new ArgentPropertySpec(name, label,
                        ArgentPropertySpec.Kind.TEXT, "<значение>", null,
                        canonical.contains("PASSWORD") || canonical.contains("SECRET") || canonical.contains("KEY"), List.of());
                String hint = nearest == null ? "Такого ключа нет в каталоге встроенных свойств service-common."
                        : "Возможно, имелось в виду полное имя: " + nearest.key();
                issues.add(new ArgentConfigurationIssue("error".equals(mode)
                        ? ArgentConfigurationIssue.Severity.ERROR
                        : ArgentConfigurationIssue.Severity.WARNING, unknown, hint));
            }
        }
    }

    private static ArgentPropertySpec nearest(Map<String, ArgentPropertySpec> known, String canonical) {
        int best = Integer.MAX_VALUE;
        ArgentPropertySpec result = null;
        for (Map.Entry<String, ArgentPropertySpec> candidate : known.entrySet()) {
            int distance = distance(canonical, candidate.getKey());
            if (distance < best) { best = distance; result = candidate.getValue(); }
        }
        return best <= 5 ? result : null;
    }

    private static String canonical(String key) {
        return key.replaceAll("\\[[0-9]+]", "_0")
                .replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] next = new int[b.length() + 1]; next[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                next[j] = Math.min(Math.min(next[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            prev = next;
        }
        return prev[b.length()];
    }
}
