package com.argent.servicecommon.attachment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.argent.servicecommon.attachment.dto.AttachmentDto;
import com.argent.servicecommon.attachment.entity.Attachment;
import com.argent.servicecommon.security.CurrentUser;
import com.argent.servicecommon.security.exception.GrantsAccessDeniedException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST вложений {@code /api/attachments}: права проверяются грантами таблицы {@code entityType}
 * ({@code read} — список и скачивание, {@code write} — загрузка и удаление).
 */
@RestController
@RequestMapping("/api/attachments")
@Tag(name = "Вложения")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = Objects.requireNonNull(service);
    }

    /** Вложения записи; без {@code table:<entityType>:read} — 403. */
    @GetMapping
    @Operation(summary = "Получить список вложений")
    @PreAuthorize("@argentAccess.table(#entityType, 'read')")
    public List<AttachmentDto> list(@RequestParam("entityType") String entityType,
                                    @RequestParam("entityId") String entityId) {
        return service.list(entityType, entityId);
    }

    /** Загрузка файла; без {@code table:<entityType>:write} — 403, недопустимый файл — 400. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить файл")
    @PreAuthorize("@argentAccess.table(#entityType, 'write')")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentDto upload(@RequestParam("entityType") String entityType,
                                @RequestParam("entityId") String entityId,
                                @RequestParam("file") MultipartFile file) {
        return service.store(entityType, entityId, file);
    }

    /** Скачивание; без права {@code read} на таблицу вложения — 404, чтобы не раскрывать его существование. */
    @GetMapping("/{id}")
    @Operation(summary = "Скачать вложение")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> download(@PathVariable("id") UUID id) {
        Attachment attachment = findReadable(id);
        Resource resource = service.open(attachment);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType(attachment.getContentType()));
        headers.setContentLength(attachment.getSizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(attachment.getFileName(), StandardCharsets.UTF_8)
                .build());
        headers.setETag("\"" + attachment.getSha256() + "\"");
        // Файл пользователя не должен исполняться в браузере в контексте приложения.
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox");
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    /** Удаление; без права {@code read} — 404, с {@code read} без {@code write} — 403. */
    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить вложение")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") UUID id) {
        Attachment attachment = findReadable(id);
        if (!CurrentUser.canTable(attachment.getEntityType(), "write")) {
            throw new GrantsAccessDeniedException("Нет права записи в таблицу " + attachment.getEntityType());
        }
        service.delete(id);
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException exception) {
            // Строка из БД могла быть испорчена вручную: отдаём файл как двоичный, а не 500.
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private Attachment findReadable(UUID id) {
        Attachment attachment = service.find(id);
        if (!CurrentUser.canTable(attachment.getEntityType(), "read")) {
            throw new EntityNotFoundException("Вложение не найдено: " + id);
        }
        return attachment;
    }
}
