package com.argent.servicecommon.attachment;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Файловое хранилище вложений: файл лежит как {@code <root>/<yyyy>/<MM>/<uuid>} без расширения,
 * ключ хранилища — этот относительный путь.
 */
public class AttachmentStorage {

    /** Папка временных файлов внутри корня: перенос из неё в итоговую папку атомарен в пределах тома. */
    static final String TMP_DIRECTORY = "tmp";

    private static final int BUFFER_SIZE = 64 * 1024;

    private static final Pattern STORAGE_KEY = Pattern.compile("\\d{4}/\\d{2}/[0-9a-f-]{36}");

    private final Path root;

    public AttachmentStorage(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /**
     * Сохраняет поток в новый файл, считая размер и sha256. Поток читается до конца, но не дальше
     * {@code maxBytes + 1} байт, и закрывается.
     *
     * @throws AttachmentTooLargeException поток длиннее {@code maxBytes}; временный файл удаляется
     * @throws UncheckedIOException        при ошибке записи; временный файл удаляется
     */
    public StoredFile store(InputStream in, long maxBytes) {
        Path tmp = null;
        try (InputStream source = in) {
            Path tmpDirectory = Files.createDirectories(root.resolve(TMP_DIRECTORY));
            tmp = Files.createTempFile(tmpDirectory, "upload-", ".part");
            MessageDigest digest = sha256();
            long size = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (OutputStream out = Files.newOutputStream(tmp)) {
                // Читаем не больше лимита + 1 байт: заявленному размеру multipart верить нельзя.
                long limit = maxBytes == Long.MAX_VALUE ? Long.MAX_VALUE : maxBytes + 1;
                int count;
                while (size < limit
                        && (count = source.read(buffer, 0, (int) Math.min(buffer.length, limit - size))) >= 0) {
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                    size += count;
                }
            }
            if (size > maxBytes) {
                Files.deleteIfExists(tmp);
                throw new AttachmentTooLargeException(maxBytes);
            }
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String storageKey = "%04d/%02d/%s".formatted(today.getYear(), today.getMonthValue(), UUID.randomUUID());
            Path target = resolve(storageKey);
            Files.createDirectories(target.getParent());
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return new StoredFile(storageKey, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException suppressed) {
                    exception.addSuppressed(suppressed);
                }
            }
            throw new UncheckedIOException("Не удалось сохранить файл вложения", exception);
        }
    }

    /** Файл по ключу хранилища; существование не проверяется. */
    public Resource open(String storageKey) {
        return new FileSystemResource(resolve(storageKey));
    }

    /**
     * Удаляет файл по ключу хранилища.
     *
     * @return {@code true}, если файл был и удалён
     * @throws UncheckedIOException при ошибке удаления
     */
    public boolean delete(String storageKey) {
        try {
            return Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось удалить файл вложения: " + storageKey, exception);
        }
    }

    private Path resolve(String storageKey) {
        // Ключ приходит из базы; проверка формата не даёт выйти за корень хранилища.
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Недопустимый ключ хранилища: " + storageKey);
        }
        return root.resolve(storageKey);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Результат записи: ключ хранилища, размер в байтах и sha256 в шестнадцатеричном виде. */
    public record StoredFile(String storageKey, long size, String sha256) {
    }
}
