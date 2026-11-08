package com.argent.servicecommon.security.jwt;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Проверяет создание, повторную загрузку и права файла ключа подписи JWT без контекста Spring.
 */
class JwtKeyProviderTest {

    @TempDir
    Path directory;

    @Test
    void shouldGenerateKeyAndReloadWithSameKeyId() {
        Path keyFile = directory.resolve("keys/argent-jwt.pem");

        RSAKey generated = JwtKeyProvider.load(keyFile);
        RSAKey reloaded = JwtKeyProvider.load(keyFile);

        assertThat(keyFile).exists();
        assertThat(generated.isPrivate()).isTrue();
        assertThat(generated.size()).isEqualTo(2048);
        assertThat(reloaded.getKeyID()).isEqualTo(generated.getKeyID());
        assertThat(reloaded.getModulus()).isEqualTo(generated.getModulus());
        assertThat(reloaded.getPrivateExponent()).isEqualTo(generated.getPrivateExponent());
    }

    @Test
    void shouldRestrictKeyFileToOwnerOnPosix() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path keyDirectory = directory.resolve("keys");
        Path keyFile = keyDirectory.resolve("argent-jwt.pem");

        JwtKeyProvider.load(keyFile);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyDirectory))).isEqualTo("rwx------");
    }
}
