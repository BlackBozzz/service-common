package com.argent.servicecommon.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Настройки цепочки фильтров безопасности ({@code argent.security.*}).
 *
 * @param permitPaths дополнительные шаблоны путей, доступные без входа
 * @param cors        настройки CORS для {@code /api/**}
 * @param actions     действия приложения для каталога подсказок в редакторе ролей
 */
@ConfigurationProperties("argent.security")
public record SecurityProperties(
        @DefaultValue List<String> permitPaths,
        @DefaultValue Cors cors,
        @DefaultValue List<String> actions
) {

    public SecurityProperties {
        permitPaths = permitPaths == null ? List.of() : List.copyOf(permitPaths);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /**
     * Настройки CORS.
     *
     * @param allowedOrigins разрешённые источники; пустой список отключает настройку CORS
     */
    public record Cors(@DefaultValue List<String> allowedOrigins) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }
}
