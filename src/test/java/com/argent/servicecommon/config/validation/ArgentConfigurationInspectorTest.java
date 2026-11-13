package com.argent.servicecommon.config.validation;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArgentConfigurationInspectorTest {
    @Test
    void nestedPropertyHasFullPathAndAllFillingOptions() {
        ArgentPropertySpec property = ArgentConfigurationCatalog.byKey()
                .get("argent.auth.bootstrap-admin.password");
        assertNotNull(property);
        String instructions = property.fillingOptions();
        assertTrue(instructions.contains("argent.auth.bootstrap-admin.password="));
        assertTrue(instructions.contains("ARGENT_AUTH_BOOTSTRAP_ADMIN_PASSWORD="));
        assertTrue(instructions.contains("  bootstrap-admin:\n"));
        assertTrue(instructions.contains("    password:"));
        assertTrue(instructions.contains("/run/secrets/argent.auth.bootstrap-admin.password"));
    }

    @Test
    void invalidNestedValueShowsFullPathAndNoSensitiveValue() {
        var env = new MockEnvironment()
                .withProperty("argent.auth.session-ttl", "0s")
                .withProperty("argent.auth.bootstrap-admin.password", "ultra-secret-123");
        var exception = assertThrows(ArgentConfigurationException.class,
                () -> ArgentConfigurationInspector.validateOrThrow(env));
        assertTrue(exception.getMessage().contains("argent.auth.session-ttl"));
        assertTrue(exception.getMessage().contains("ARGENT_AUTH_SESSION_TTL"));
        assertFalse(exception.getMessage().contains("ultra-secret-123"));
    }

    @Test
    void mailRequiresHostSenderAndAuthSecretsOnlyWhenEnabled() {
        var disabled = new MockEnvironment().withProperty("argent.mail.enabled", "false");
        assertTrue(ArgentConfigurationInspector.inspect(disabled).stream().noneMatch(i ->
                i.property().key().equals("spring.mail.host")));

        var enabled = new MockEnvironment()
                .withProperty("argent.mail.enabled", "true")
                .withProperty("spring.mail.properties.mail.smtp.auth", "true");
        var errors = ArgentConfigurationInspector.inspect(enabled).stream()
                .filter(i -> i.severity() == ArgentConfigurationIssue.Severity.ERROR)
                .map(i -> i.property().key()).toList();
        assertTrue(errors.contains("spring.mail.host"));
        assertTrue(errors.contains("argent.mail.from"));
        assertTrue(errors.contains("spring.mail.username"));
        assertTrue(errors.contains("spring.mail.password"));
    }

    @Test
    void invalidQueueConfigDoesNotSilentlyClamp() {
        var env = new MockEnvironment()
                .withProperty("argent.queue.poll-interval-ms", "50")
                .withProperty("argent.queue.lease-seconds", "0");
        assertEquals(2, ArgentConfigurationInspector.inspect(env).stream()
                .filter(i -> i.severity() == ArgentConfigurationIssue.Severity.ERROR).count());
    }

    @Test
    void detectsUnknownArgentPropertyAndSuggestsKnownFullName() {
        var env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "argent.auth.bootstrap-admin.pasword", "secret-value")));
        String issues = ArgentConfigurationInspector.describeIssues(ArgentConfigurationInspector.inspect(env));
        assertTrue(issues.contains("argent.auth.bootstrap-admin.password"));
        assertFalse(issues.contains("secret-value"));
    }

    @Test
    void validConfigurationIsAccepted() {
        var env = new MockEnvironment()
                .withProperty("argent.auth.session-ttl", "30d")
                .withProperty("argent.mail.enabled", "true")
                .withProperty("argent.mail.from", "noreply@example.org")
                .withProperty("spring.mail.host", "smtp.example.org")
                .withProperty("spring.mail.port", "587")
                .withProperty("argent.queue.max-concurrent-queues", "4");
        assertDoesNotThrow(() -> ArgentConfigurationInspector.validateOrThrow(env));
    }
}
