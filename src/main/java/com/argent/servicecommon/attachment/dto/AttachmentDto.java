package com.argent.servicecommon.attachment.dto;

import com.argent.servicecommon.attachment.entity.Attachment;

import java.time.Instant;
import java.util.UUID;

/** Сведения о вложении без пути в хранилище и контрольной суммы. */
public record AttachmentDto(
        UUID id,
        String entityType,
        String entityId,
        String fileName,
        String contentType,
        long sizeBytes,
        String uploadedLogin,
        Instant createdAt
) {

    public static AttachmentDto from(Attachment attachment) {
        return new AttachmentDto(attachment.getId(), attachment.getEntityType(), attachment.getEntityId(),
                attachment.getFileName(), attachment.getContentType(), attachment.getSizeBytes(),
                attachment.getUploadedLogin(), attachment.getCreatedAt());
    }
}
