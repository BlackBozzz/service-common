package com.argent.servicecommon.attachment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Файл, прикреплённый к записи любой таблицы приложения: привязка строками без внешнего ключа, файл — в хранилище
 * под {@link #storageKey}. Не справочник: в админке справочников не показывается.
 */
@Entity
@Table(name = "common_attachment")
@Getter
@Setter
@NoArgsConstructor
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Имя таблицы записи-владельца. */
    @Column(name = "entity_type", nullable = false, length = 63)
    private String entityType;

    /** Идентификатор записи-владельца. */
    @Column(name = "entity_id", nullable = false)
    private String entityId;

    /** Нормализованное имя файла, под которым он был загружен. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** MIME-тип, определённый при загрузке. */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Относительный путь файла в хранилище: {@code yyyy/MM/<uuid>}. */
    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_login")
    private String uploadedLogin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
