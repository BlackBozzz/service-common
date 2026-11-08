package com.argent.servicecommon.security.repository;

import com.argent.servicecommon.security.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;

/**
 * Репозиторий: Role.
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(String code);

    List<Role> findBySystemTrue();

    /** Коды и JSON-гранты всех ролей пользователя (через {@code common_user_role}). */
    @Query("select new com.argent.servicecommon.security.repository.RoleGrantsRow(r.code, r.grants)"
            + " from Role r, UserRole ur where ur.id.roleId = r.id and ur.id.userId = :userId")
    List<RoleGrantsRow> findGrantsByUserId(@Param("userId") UUID userId);
}
