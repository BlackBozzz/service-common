package com.argent.servicecommon.status.repository;

import com.argent.servicecommon.status.entity.CommonStatusTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Репозиторий переходов статусов. */
public interface CommonStatusTransitionRepository extends JpaRepository<CommonStatusTransition, UUID> {

    List<CommonStatusTransition> findByStatusTypeId(UUID statusTypeId);
}
