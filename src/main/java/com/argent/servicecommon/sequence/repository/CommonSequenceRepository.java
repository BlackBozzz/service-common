package com.argent.servicecommon.sequence.repository;

import com.argent.servicecommon.sequence.entity.CommonSequence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Репозиторий счётчиков номеров. */
public interface CommonSequenceRepository extends JpaRepository<CommonSequence, UUID> {

    Optional<CommonSequence> findByCode(String code);
}
