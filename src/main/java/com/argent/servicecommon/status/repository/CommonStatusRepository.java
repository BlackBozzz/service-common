package com.argent.servicecommon.status.repository;

import com.argent.servicecommon.status.entity.CommonStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Репозиторий статусов. */
public interface CommonStatusRepository extends JpaRepository<CommonStatus, UUID> {

    Optional<CommonStatus> findByStatusTypeIdAndCode(UUID statusTypeId, String code);

    List<CommonStatus> findByStatusTypeIdOrderBySortOrder(UUID statusTypeId);
}
