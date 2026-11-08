package com.argent.servicecommon.attachment.repository;

import com.argent.servicecommon.attachment.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Репозиторий вложений. */
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, String entityId);
}
