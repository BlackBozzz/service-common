package com.argent.servicecommon.audit.repository;

import com.argent.servicecommon.audit.entity.AuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/** Репозиторий журнала аудита; фильтрация через JPA Specification. */
public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID>,
        JpaSpecificationExecutor<AuditEntry> {
}
