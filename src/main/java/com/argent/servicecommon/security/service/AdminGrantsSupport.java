package com.argent.servicecommon.security.service;

import com.argent.servicecommon.error.ValidationErrorsException;
import com.argent.servicecommon.security.CurrentUser;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.grants.GrantsAuthorities;
import com.argent.servicecommon.security.grants.GrantsCodec;
import com.argent.servicecommon.security.grants.RoleGrants;
import com.argent.servicecommon.security.repository.RoleGrantsRow;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Общие вычисления для правил администрирования: эффективные гранты пользователей по данным базы
 * и проверка наличия активного администратора.
 *
 * <p>Роль с некорректными грантами в базе считается ролью без прав (с записью ERROR в журнал) —
 * так же, как при построении authorities.</p>
 */
final class AdminGrantsSupport {

    /** Сообщение об отказе оставить систему без активного администратора. */
    static final String LAST_ADMIN_MESSAGE = "Нельзя оставить систему без активного администратора";

    private static final Logger LOG = LoggerFactory.getLogger(AdminGrantsSupport.class);

    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    AdminGrantsSupport(RoleRepository roleRepository, UserRoleRepository userRoleRepository) {
        this.roleRepository = Objects.requireNonNull(roleRepository);
        this.userRoleRepository = Objects.requireNonNull(userRoleRepository);
    }

    /** Идентификатор вызывающего пользователя, если запрос аутентифицирован. */
    Optional<UUID> callerId() {
        return CurrentUser.id();
    }

    /** Эффективные гранты вызывающего пользователя; для анонимного вызова — без прав. */
    RoleGrants callerGrants() {
        return callerId().map(this::effectiveGrants).orElse(RoleGrants.empty());
    }

    /** Объединённые гранты всех ролей пользователя. */
    RoleGrants effectiveGrants(UUID userId) {
        List<RoleGrants> grants = new ArrayList<>();
        for (RoleGrantsRow row : roleRepository.findGrantsByUserId(userId)) {
            grants.add(parseLenient(row.code(), row.grants()));
        }
        return GrantsAuthorities.merge(grants);
    }

    /** Гранты роли; некорректные гранты в базе — без прав. */
    static RoleGrants parseLenient(String code, String json) {
        try {
            return GrantsCodec.parse(json);
        } catch (ValidationErrorsException exception) {
            LOG.error("Некорректные гранты роли {} считаются пустыми: {} ({})", code, json, exception.getErrors());
            return RoleGrants.empty();
        }
    }

    /** Есть ли в грантах встроенное действие {@code admin}. */
    static boolean hasAdminAction(RoleGrants grants) {
        return grants.actions().contains(GrantConstants.ADMIN_ACTION);
    }

    /**
     * Останется ли активный пользователь с действием {@code admin}, если не учитывать указанного
     * пользователя и указанную роль.
     *
     * @param excludedUserId пользователь, теряющий действие (отключается или лишается ролей), или {@code null}
     * @param excludedRoleId роль, теряющая действие, или {@code null}
     */
    boolean hasOtherEnabledAdmin(UUID excludedUserId, UUID excludedRoleId) {
        List<UUID> adminRoleIds = roleRepository.findAll().stream()
                .filter(role -> !role.getId().equals(excludedRoleId))
                .filter(role -> hasAdminAction(parseLenient(role.getCode(), role.getGrants())))
                .map(Role::getId)
                .toList();
        if (adminRoleIds.isEmpty()) {
            return false;
        }
        return userRoleRepository.findEnabledUserIdsByRoleIds(adminRoleIds).stream()
                .anyMatch(userId -> !userId.equals(excludedUserId));
    }
}
