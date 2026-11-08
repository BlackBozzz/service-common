package com.argent.servicecommon.audit.dto;

import com.argent.servicecommon.audit.entity.AuditEntry;

import java.time.Instant;
import java.util.UUID;

/** Строка журнала аудита для REST {@code GET /api/audit}. */
public record AuditEntryDto(
        UUID id,
        String entityType,
        String entityId,
        String action,
        String field,
        String oldValue,
        String newValue,
        UUID actorId,
        String actorLogin,
        Instant createdAt
) {

    /** Копирует запись журнала в DTO. */
    public static AuditEntryDto from(AuditEntry entry) {
        return new AuditEntryDto(entry.getId(), entry.getEntityType(), entry.getEntityId(), entry.getAction(),
                entry.getField(), entry.getOldValue(), entry.getNewValue(), entry.getActorId(),
                entry.getActorLogin(), entry.getCreatedAt());
    }
}
