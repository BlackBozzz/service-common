package com.argent.servicecommon.attachment;

import com.argent.servicecommon.attachment.dto.AttachmentDto;
import com.argent.servicecommon.attachment.entity.Attachment;
import com.argent.servicecommon.attachment.repository.AttachmentRepository;
import com.argent.servicecommon.audit.AuditWriter;
import com.argent.servicecommon.security.CurrentUser;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Вложения к записям таблиц приложения: файл в {@link AttachmentStorage} и строка {@code common_attachment}.
 * Права не проверяет (это делает контроллер); загрузка и удаление пишут {@code ATTACH}/{@code DETACH}
 * в аудит владельца.
 */
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private static final Pattern ENTITY_TYPE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final int MAX_ENTITY_ID_LENGTH = 255;
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String DEFAULT_FILE_NAME = "file";
    private static final String OCTET_STREAM = "application/octet-stream";
    // Типы, которые браузер может исполнить или отрисовать как активное содержимое; плюс любые *+xml.
    private static final Set<String> BLOCKED_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml",
            "text/javascript", "application/javascript", "application/ecmascript",
            "application/x-msdownload", "application/x-msdos-program", "application/x-dosexec",
            "application/x-sh", "application/vnd.microsoft.portable-executable", "application/x-executable");
    private static final int MAX_TYPE_LENGTH = 255;
    private static final int HEAD_LENGTH = 8;

    private final AttachmentRepository repository;
    private final AttachmentStorage storage;
    private final AttachmentProperties properties;
    private final ObjectProvider<AuditWriter> auditWriter;

    public AttachmentService(AttachmentRepository repository, AttachmentStorage storage,
                             AttachmentProperties properties, ObjectProvider<AuditWriter> auditWriter) {
        this.repository = Objects.requireNonNull(repository);
        this.storage = Objects.requireNonNull(storage);
        this.properties = Objects.requireNonNull(properties);
        this.auditWriter = Objects.requireNonNull(auditWriter);
    }

    /** Вложения записи в порядке загрузки; недопустимый владелец — {@link IllegalArgumentException}. */
    @Transactional(readOnly = true)
    public List<AttachmentDto> list(String entityType, String entityId) {
        checkOwner(entityType, entityId);
        return repository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(entityType, entityId).stream()
                .map(AttachmentDto::from)
                .toList();
    }

    /**
     * Сохраняет файл и строку вложения. Файл пишется до вставки строки; если вставка или аудит
     * не удались либо транзакция откатилась, файл удаляется.
     *
     * @throws IllegalArgumentException пустой файл, превышен размер, тип не разрешён, недопустимый владелец
     * @throws UncheckedIOException     ошибка записи файла
     */
    @Transactional
    public AttachmentDto store(String entityType, String entityId, MultipartFile file) {
        checkOwner(entityType, entityId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл пуст");
        }
        if (file.getSize() > properties.maxSizeBytes()) {
            throw new IllegalArgumentException("Файл превышает допустимый размер");
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        String declaredType = normalizeType(file.getContentType());
        String contentType = detectContentType(readHead(file), fileName, declaredType);
        checkContentType(contentType, declaredType);

        AttachmentStorage.StoredFile stored;
        try {
            stored = storage.store(file.getInputStream(), properties.maxSizeBytes());
        } catch (AttachmentTooLargeException exception) {
            throw new IllegalArgumentException("Файл превышает допустимый размер", exception);
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось прочитать загружаемый файл", exception);
        }
        try {
            Attachment attachment = new Attachment();
            attachment.setEntityType(entityType);
            attachment.setEntityId(entityId);
            attachment.setFileName(fileName);
            attachment.setContentType(contentType);
            attachment.setSizeBytes(stored.size());
            attachment.setStorageKey(stored.storageKey());
            attachment.setSha256(stored.sha256());
            attachment.setUploadedBy(CurrentUser.id().orElse(null));
            attachment.setUploadedLogin(CurrentUser.login().orElse(null));
            // saveAndFlush: ошибка вставки должна возникнуть здесь, пока можно удалить записанный файл.
            Attachment saved = repository.saveAndFlush(attachment);
            audit(entityType, entityId, "ATTACH", null, fileName);
            deleteFileOnRollback(stored.storageKey());
            return AttachmentDto.from(saved);
        } catch (RuntimeException | Error exception) {
            deleteFileQuietly(stored.storageKey());
            throw exception;
        }
    }

    /**
     * Строка вложения по идентификатору.
     *
     * @throws EntityNotFoundException вложения нет
     */
    @Transactional(readOnly = true)
    public Attachment find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Вложение не найдено: " + id));
    }

    /**
     * Файл вложения.
     *
     * @throws EntityNotFoundException файла нет в хранилище
     */
    public Resource open(Attachment attachment) {
        Resource resource = storage.open(attachment.getStorageKey());
        if (!resource.exists()) {
            throw new EntityNotFoundException("Файл вложения не найден: " + attachment.getId());
        }
        return resource;
    }

    /**
     * Удаляет строку вложения; файл удаляется после коммита, ошибка удаления файла пишется в журнал.
     *
     * @throws EntityNotFoundException вложения нет
     */
    @Transactional
    public void delete(UUID id) {
        Attachment attachment = find(id);
        repository.delete(attachment);
        repository.flush();
        audit(attachment.getEntityType(), attachment.getEntityId(), "DETACH", attachment.getFileName(), null);
        deleteFilesAfterCommit(List.of(attachment.getStorageKey()));
    }

    /**
     * Удаляет все вложения записи (для вызова приложением при удалении записи-владельца); файлы
     * удаляются после коммита.
     *
     * @return число удалённых вложений
     * @throws IllegalArgumentException недопустимое имя таблицы или идентификатор записи
     */
    @Transactional
    public int deleteAll(String entityType, String entityId) {
        checkOwner(entityType, entityId);
        List<Attachment> attachments =
                repository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(entityType, entityId);
        repository.deleteAll(attachments);
        repository.flush();
        for (Attachment attachment : attachments) {
            audit(entityType, entityId, "DETACH", attachment.getFileName(), null);
        }
        deleteFilesAfterCommit(attachments.stream().map(Attachment::getStorageKey).toList());
        return attachments.size();
    }

    static String sanitizeFileName(String originalName) {
        if (originalName == null) {
            return DEFAULT_FILE_NAME;
        }
        StringBuilder cleaned = new StringBuilder(originalName.length());
        // Управляющие (Cc) и форматирующие (Cf, в том числе bidi U+202E) символы подменяют видимое имя.
        originalName.replace('\\', '/').codePoints()
                .filter(codePoint -> Character.getType(codePoint) != Character.CONTROL
                        && Character.getType(codePoint) != Character.FORMAT
                        && "\"<>:|?*".indexOf(codePoint) < 0)
                .forEach(cleaned::appendCodePoint);
        String name;
        try {
            // Запрещённые символы уже удалены, поэтому путь разбирается и на Windows.
            Path fileName = Path.of(cleaned.toString()).getFileName();
            name = fileName == null ? "" : fileName.toString().strip();
        } catch (InvalidPathException exception) {
            String value = cleaned.toString();
            name = value.substring(value.lastIndexOf('/') + 1).strip();
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return DEFAULT_FILE_NAME;
        }
        return truncate(name);
    }

    private static String truncate(String name) {
        if (name.length() <= MAX_FILE_NAME_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
        return name.substring(0, MAX_FILE_NAME_LENGTH - extension.length()) + extension;
    }

    private String detectContentType(byte[] head, String fileName, String declaredType) {
        // Исполняемые файлы Windows (PE/MZ) и Linux (ELF) блокируются независимо от имени и заявленного типа.
        if (startsWith(head, new byte[]{'M', 'Z'})) {
            return "application/x-msdownload";
        }
        if (startsWith(head, new byte[]{0x7f, 'E', 'L', 'F'})) {
            return "application/x-executable";
        }
        if (startsWith(head, "%PDF".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (startsWith(head, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
            return "image/png";
        }
        if (startsWith(head, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(head, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(head, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return "image/gif";
        }
        String probed = probeContentType(fileName);
        if (startsWith(head, new byte[]{'P', 'K', 3, 4})) {
            // Документы Office и OpenDocument — тоже ZIP; их точный тип даёт только расширение.
            return probed != null && probed.startsWith("application/vnd.") ? probed : "application/zip";
        }
        if (probed != null) {
            return probed;
        }
        // Заявленному клиентом типу верим, только если его явно разрешил непустой allowlist.
        return declaredType != null && !properties.allowedTypes().isEmpty() && isAllowed(declaredType)
                ? declaredType : OCTET_STREAM;
    }

    private static String probeContentType(String fileName) {
        try {
            return normalizeType(Files.probeContentType(Path.of(fileName)));
        } catch (IOException | InvalidPathException exception) {
            return null;
        }
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return null;
        }
        if (type.length() > MAX_TYPE_LENGTH) {
            throw new IllegalArgumentException("Тип файла не разрешён");
        }
        int parameters = type.indexOf(';');
        String value = (parameters >= 0 ? type.substring(0, parameters) : type).strip().toLowerCase(Locale.ROOT);
        return value.isEmpty() || !value.contains("/") ? null : value;
    }

    private void checkContentType(String contentType, String declaredType) {
        if (isBlocked(contentType) || !isAllowed(contentType)) {
            throw new IllegalArgumentException("Тип файла не разрешён: " + contentType);
        }
        // Заявленный тип тоже проверяется: ему может поверить браузер или клиент приложения.
        if (declaredType != null && isBlocked(declaredType)) {
            throw new IllegalArgumentException("Тип файла не разрешён: " + declaredType);
        }
    }

    private static boolean isBlocked(String type) {
        return BLOCKED_TYPES.contains(type) || type.endsWith("+xml");
    }

    private boolean isAllowed(String type) {
        return properties.allowedTypes().isEmpty()
                || properties.allowedTypes().stream().anyMatch(allowedType -> matches(allowedType, type));
    }

    private static boolean matches(String allowedType, String contentType) {
        String pattern = allowedType.endsWith("/*") ? allowedType.substring(0, allowedType.length() - 1) : allowedType;
        return pattern.endsWith("/") ? contentType.startsWith(pattern) : contentType.equals(pattern);
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        return head.length >= signature.length
                && Arrays.equals(head, 0, signature.length, signature, 0, signature.length);
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_LENGTH);
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось прочитать загружаемый файл", exception);
        }
    }

    private static void checkOwner(String entityType, String entityId) {
        if (entityType == null || !ENTITY_TYPE.matcher(entityType).matches()) {
            throw new IllegalArgumentException("Недопустимое имя таблицы: " + entityType);
        }
        if (entityId == null || entityId.isBlank() || entityId.length() > MAX_ENTITY_ID_LENGTH) {
            throw new IllegalArgumentException("Недопустимый идентификатор записи: " + entityId);
        }
    }

    private void audit(String entityType, String entityId, String action, String oldValue, String newValue) {
        auditWriter.ifAvailable(writer -> writer.write(entityType, entityId, action, null, oldValue, newValue));
    }

    private void deleteFileOnRollback(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // STATUS_UNKNOWN не значит откат: строка могла зафиксироваться, файл не трогаем.
                if (status == STATUS_ROLLED_BACK) {
                    deleteFileQuietly(storageKey);
                }
            }
        });
    }

    private void deleteFilesAfterCommit(List<String> storageKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storageKeys.forEach(this::deleteFileQuietly);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageKeys.forEach(AttachmentService.this::deleteFileQuietly);
            }
        });
    }

    private void deleteFileQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException exception) {
            log.warn("Не удалось удалить файл вложения {}", storageKey, exception);
        }
    }
}
