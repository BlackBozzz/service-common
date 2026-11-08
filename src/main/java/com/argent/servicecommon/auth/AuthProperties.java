package com.argent.servicecommon.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Настройки аутентификации ({@code argent.auth.*}).
 *
 * @param passwordRequired требуется ли пароль при входе; {@code false} допускает вход только по логину
 * @param registration     настройки самостоятельной регистрации пользователей
 * @param bootstrapAdmin   учётная запись администратора, создаваемая при первом запуске
 * @param sessionTtl       срок действия сессии и выданного для неё токена
 * @param jwt              настройки подписи JWT
 * @param rolesFile        ресурс со стартовыми ролями приложения (JSON-массив ролей с грантами);
 *                         файл необязателен, если его нет — роли не загружаются
 * @param rolesFileMode    режим загрузки ролей из {@code rolesFile}
 */
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

    /**
     * Настройки самостоятельной регистрации.
     *
     * @param enabled      разрешена ли регистрация через {@code /api/auth/register}
     * @param defaultRoles коды ролей, назначаемых новому пользователю
     */
    public record Registration(
            @DefaultValue("false") boolean enabled,
            @DefaultValue List<String> defaultRoles
    ) {
        public Registration {
            defaultRoles = defaultRoles == null ? List.of() : List.copyOf(defaultRoles);
        }
    }

    /**
     * Администратор, создаваемый при первом запуске.
     *
     * @param login    логин администратора
     * @param password пароль администратора; пустое значение означает, что пароль не задан
     */
    public record BootstrapAdmin(
            @DefaultValue("admin") String login,
            @DefaultValue("") String password
    ) {
        public BootstrapAdmin {
            password = password == null ? "" : password;
        }
    }

    /**
     * Настройки подписи JWT.
     *
     * @param keyFile путь к закрытому RSA-ключу в формате PEM PKCS#8; если файла нет, ключ создаётся
     */
    public record Jwt(@DefaultValue("./config/argent-jwt.pem") Path keyFile) {
    }
}
