package com.argent.servicecommon.security.repository;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;

/**
 * Репозиторий: UserRole.
 */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    List<UserRole> findByIdUserId(UUID userId);

    void deleteByIdUserId(UUID userId);

    boolean existsByIdRoleId(UUID roleId);

    /** Число активных пользователей, которым назначена роль. */
    @Query("""
            select count(u) from User u, UserRole ur
            where ur.id.userId = u.id and ur.id.roleId = :roleId and u.enabled = true
            """)
    long countEnabledUsersByRoleId(@Param("roleId") UUID roleId);

    /** Идентификаторы активных пользователей, которым назначена хотя бы одна из ролей. */
    @Query("""
            select distinct u.id from User u, UserRole ur
            where ur.id.userId = u.id and ur.id.roleId in :roleIds and u.enabled = true
            """)
    List<UUID> findEnabledUserIdsByRoleIds(@Param("roleIds") Collection<UUID> roleIds);

    /** Пользователи, имеющие хотя бы одну из ролей; исключаем повторы по нескольким ролям. */
    @Query("""
            select distinct u from User u, UserRole ur, Role r
            where ur.id.userId = u.id and ur.id.roleId = r.id and r.code in :roleCodes
            """)
    List<User> findUsersByRoleCodes(@Param("roleCodes") Collection<String> roleCodes);
}
