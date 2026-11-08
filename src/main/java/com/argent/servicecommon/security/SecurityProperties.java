package com.argent.servicecommon.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** Настройки цепочки фильтров {@code argent.security.*}: открытые пути, CORS и действия для редактора ролей. */
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

    /** CORS для {@code /api/**}; пустой список источников — CORS не настраивается. */
    public record Cors(@DefaultValue List<String> allowedOrigins) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }
}
