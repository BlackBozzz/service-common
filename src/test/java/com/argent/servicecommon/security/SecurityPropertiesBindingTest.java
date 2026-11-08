package com.argent.servicecommon.security;

import com.argent.servicecommon.auth.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет значения по умолчанию и привязку свойств {@code argent.auth.*} и {@code argent.security.*}.
 */
class SecurityPropertiesBindingTest {

    @Test
    void shouldApplyDefaultsWhenNothingConfigured() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));

        AuthProperties auth = binder.bindOrCreate("argent.auth", Bindable.of(AuthProperties.class));
        SecurityProperties security = binder.bindOrCreate("argent.security", Bindable.of(SecurityProperties.class));

        assertThat(auth.passwordRequired()).isTrue();
        assertThat(auth.registration().enabled()).isFalse();
        assertThat(auth.registration().defaultRoles()).isEmpty();
        assertThat(auth.bootstrapAdmin().login()).isEqualTo("admin");
        assertThat(auth.bootstrapAdmin().password()).isEmpty();
        assertThat(auth.sessionTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(auth.jwt().keyFile().normalize()).isEqualTo(Path.of("config", "argent-jwt.pem"));
        assertThat(auth.rolesFile()).isEqualTo("classpath:argent-roles.json");
        assertThat(auth.rolesFileMode()).isEqualTo(AuthProperties.RolesFileMode.CREATE);

        assertThat(security.permitPaths()).isEmpty();
        assertThat(security.cors().allowedOrigins()).isEmpty();
        assertThat(security.actions()).isEmpty();
    }

    @Test
    void shouldBindConfiguredValues() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "argent.auth.session-ttl", "8h",
                "argent.auth.registration.enabled", "true",
                "argent.auth.registration.default-roles", "USER,VIEWER",
                "argent.security.permit-paths", "/api/public/**",
                "argent.security.cors.allowed-origins", "http://localhost:4200",
                "argent.auth.roles-file", "file:./roles.json",
                "argent.auth.roles-file-mode", "overwrite",
                "argent.security.actions", "documents.approve,showcase.publish")));

        AuthProperties auth = binder.bindOrCreate("argent.auth", Bindable.of(AuthProperties.class));
        SecurityProperties security = binder.bindOrCreate("argent.security", Bindable.of(SecurityProperties.class));

        assertThat(auth.sessionTtl()).isEqualTo(Duration.ofHours(8));
        assertThat(auth.registration().enabled()).isTrue();
        assertThat(auth.registration().defaultRoles()).containsExactly("USER", "VIEWER");
        assertThat(auth.rolesFile()).isEqualTo("file:./roles.json");
        assertThat(auth.rolesFileMode()).isEqualTo(AuthProperties.RolesFileMode.OVERWRITE);
        assertThat(security.permitPaths()).containsExactly("/api/public/**");
        assertThat(security.cors().allowedOrigins()).containsExactly("http://localhost:4200");
        assertThat(security.actions()).containsExactly("documents.approve", "showcase.publish");
    }
}
