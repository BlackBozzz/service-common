package com.argent.servicecommon.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Настройки аутентификации {@code argent.auth.*}; описание и умолчания — {@code docs/reference.md}. */
@ConfigurationProperties("argent.auth")
public record AuthProperties(
        @DefaultValue("true") boolean passwordRequired,
        @DefaultValue Registration registration,
        @DefaultValue BootstrapAdmin bootstrapAdmin,
        @DefaultValue("30d") Duration sessionTtl,
        @DefaultValue Jwt jwt,
        @DefaultValue("classpath:argent-roles.json") String rolesFile,
        @DefaultValue("CREATE") RolesFileMode rolesFileMode
) {

    /** Режим загрузки стартовых ролей из {@code argent.auth.roles-file}. */
    public enum RolesFileMode {
        /** Создавать только роли, которых ещё нет по коду; существующие не трогать. */
        CREATE,
        /** Приводить имя, описание и гранты существующих ролей к содержимому файла. */
        OVERWRITE
    }

    /** Самостоятельная регистрация. */
    public record Registration(
            @DefaultValue("false") boolean enabled,
            @DefaultValue List<String> defaultRoles
    ) {
        public Registration {
            defaultRoles = defaultRoles == null ? List.of() : List.copyOf(defaultRoles);
        }
    }

    /** Администратор, создаваемый при первом запуске; пустой пароль — пароль не задан. */
    public record BootstrapAdmin(
            @DefaultValue("admin") String login,
            @DefaultValue("") String password
    ) {
        public BootstrapAdmin {
            password = password == null ? "" : password;
        }
    }

    /** Ключ подписи JWT: PEM PKCS#8, создаётся, если файла нет. */
    public record Jwt(@DefaultValue("./config/argent-jwt.pem") Path keyFile) {
    }
}
