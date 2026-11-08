package com.argent.servicecommon.status.repository;

import com.argent.servicecommon.status.entity.CommonStatusType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Репозиторий типов статусов. */
public interface CommonStatusTypeRepository extends JpaRepository<CommonStatusType, UUID> {

    Optional<CommonStatusType> findByCode(String code);
}
