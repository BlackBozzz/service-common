package com.argent.servicecommon.security.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Set;

/**
 * Загружает RSA-ключ для подписи JWT из PEM-файла или создаёт новый.
 *
 * <p>Файл хранит закрытый ключ в формате PKCS#8 ({@code -----BEGIN PRIVATE KEY-----});
 * открытый ключ вычисляется из модуля и открытой экспоненты закрытого ключа.
 * Идентификатор ключа ({@code kid}) — отпечаток RFC 7638, поэтому он одинаков
 * при каждой загрузке одного и того же файла.</p>
 *
 * <p>На файловых системах с атрибутами POSIX новый файл создаётся с правами {@code rw-------},
 * а недостающие каталоги — с {@code rwx------}; на остальных (Windows) действуют права по умолчанию.
 * Если существующий файл читают группа или остальные пользователи, в журнал пишется предупреждение.</p>
 */
@Slf4j
public final class JwtKeyProvider {

    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";
    private static final int KEY_SIZE = 2048;
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------");

    private JwtKeyProvider() {
    }

    /**
     * Читает ключ из файла; если файла нет, создаёт ключ RSA-2048 и сохраняет его.
     *
     * <p>Если сохранить новый ключ не удалось, в журнал пишется предупреждение и возвращается
     * ключ, существующий только в памяти: после перезапуска выданные токены станут недействительны.</p>
     *
     * @param keyFile путь к PEM-файлу закрытого ключа
     * @return пара ключей с вычисленным идентификатором
     * @throws IllegalStateException если файл существует, но не читается или не содержит RSA-ключ PKCS#8
     */
    public static RSAKey load(Path keyFile) {
        if (Files.exists(keyFile)) {
            return read(keyFile);
        }
        RSAKey key = generate();
        try {
            write(keyFile, key);
            log.info("Создан новый ключ подписи JWT: {}", keyFile.toAbsolutePath());
        } catch (FileAlreadyExistsException exception) {
            // Файл успел создать другой процесс — используем его ключ, чтобы токены совпадали.
            return read(keyFile);
        } catch (IOException exception) {
            log.warn("Не удалось сохранить ключ подписи JWT в {}: {}. Ключ хранится только в памяти, "
                    + "после перезапуска выданные токены станут недействительны.",
                    keyFile.toAbsolutePath(), exception.getMessage());
        }
        return key;
    }

    private static RSAKey read(Path keyFile) {
        warnIfReadableByOthers(keyFile);
        try {
            String pem = Files.readString(keyFile, StandardCharsets.US_ASCII);
            int begin = pem.indexOf(PEM_BEGIN);
            int end = pem.indexOf(PEM_END);
            if (begin < 0 || end < begin) {
                throw new IllegalStateException("Файл " + keyFile.toAbsolutePath()
                        + " не содержит закрытый ключ в формате PEM PKCS#8");
            }
            byte[] der = Base64.getMimeDecoder().decode(pem.substring(begin + PEM_BEGIN.length(), end));
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (!(factory.generatePrivate(new PKCS8EncodedKeySpec(der)) instanceof RSAPrivateCrtKey privateKey)) {
                throw new IllegalStateException("Ключ в файле " + keyFile.toAbsolutePath()
                        + " не содержит параметров CRT, открытый ключ вычислить нельзя");
            }
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return build(publicKey, privateKey);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Не удалось прочитать ключ подписи JWT из "
                    + keyFile.toAbsolutePath(), exception);
        }
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();
            return build((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Алгоритм RSA недоступен", exception);
        }
    }

    private static RSAKey build(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey) {
        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Не удалось вычислить идентификатор ключа JWT", exception);
        }
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Предупреждает, если файл ключа читают группа или остальные пользователи; запуск не прерывается. */
    private static void warnIfReadableByOthers(Path keyFile) {
        if (!supportsPosix(keyFile)) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn("Файл ключа подписи JWT {} доступен для чтения группе или другим пользователям ({}). "
                        + "Рекомендуется ограничить права до rw-------.",
                        keyFile.toAbsolutePath(), PosixFilePermissions.toString(permissions));
            }
        } catch (IOException exception) {
            log.warn("Не удалось проверить права файла ключа подписи JWT {}: {}",
                    keyFile.toAbsolutePath(), exception.getMessage());
        }
    }

    private static void write(Path keyFile, RSAKey key) throws IOException {
        try {
            byte[] der = key.toPrivateKey().getEncoded();
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
            String pem = PEM_BEGIN + "\n" + body + "\n" + PEM_END + "\n";
            Path parent = keyFile.toAbsolutePath().getParent();
            if (supportsPosix(keyFile)) {
                // Закрытый ключ доступен только владельцу процесса.
                if (parent != null) {
                    Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY));
                }
                Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(OWNER_FILE));
                try {
                    Files.writeString(keyFile, pem, StandardCharsets.US_ASCII,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException exception) {
                    // Не оставляем пустой файл: при следующем запуске он не прочитался бы.
                    Files.deleteIfExists(keyFile);
                    throw exception;
                }
            } else {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(keyFile, pem, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            }
        } catch (JOSEException exception) {
            throw new IllegalStateException("Не удалось получить закрытый ключ JWT", exception);
        }
    }
}
