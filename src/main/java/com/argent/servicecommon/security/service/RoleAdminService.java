package com.argent.servicecommon.security.service;

import com.argent.servicecommon.auth.dto.GrantsDto;
import com.argent.servicecommon.error.ValidationErrorsException;
import com.argent.servicecommon.security.dto.RoleDto;
import com.argent.servicecommon.security.dto.RoleRequest;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.exception.GrantsAccessDeniedException;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.grants.GrantsAuthorities;
import com.argent.servicecommon.security.grants.GrantsCodec;
import com.argent.servicecommon.security.grants.RoleGrants;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Администрирование ролей: гранты в пределах прав вызывающего (при изменении — только добавленные), системная роль
 * не удаляется и не сужается, назначенная роль не удаляется, последний активный {@code admin} не теряется.
 */
public class RoleAdminService {

    /** Допустимый код роли. */
    public static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final AdminGrantsSupport support;

    public RoleAdminService(RoleRepository roleRepository, UserRoleRepository userRoleRepository) {
        this.roleRepository = Objects.requireNonNull(roleRepository);
        this.userRoleRepository = Objects.requireNonNull(userRoleRepository);
        this.support = new AdminGrantsSupport(roleRepository, userRoleRepository);
    }

    /** Все роли, отсортированные по коду. */
    @Transactional(readOnly = true)
    public List<RoleDto> list() {
        return roleRepository.findAll(Sort.by("code")).stream()
                .map(RoleAdminService::toDto)
                .toList();
    }

    /**
     * Роль по коду.
     *
     * @throws EntityNotFoundException если роли нет
     */
    @Transactional(readOnly = true)
    public RoleDto get(String code) {
        return toDto(findRole(code));
    }

    /**
     * Создаёт роль с указанным кодом или изменяет существующую.
     *
     * @throws ValidationErrorsException   если код или гранты некорректны (поля {@code code}, {@code grants*})
     * @throws GrantsAccessDeniedException если роль выдаёт права, которых нет у вызывающего
     * @throws IllegalStateException       если изменение оставит систему без активного администратора
     */
    @Transactional
    public RoleDto upsert(String code, RoleRequest request) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new ValidationErrorsException(Map.of("code", "Недопустимый код роли"));
        }
        RoleGrants requested = parseRequested(request.grants());
        Role role = roleRepository.findByCode(code).orElse(null);
        RoleGrants caller = support.callerGrants();

        if (role == null) {
            ensureCovered(caller, requested);
            role = new Role();
            role.setCode(code);
            role.setSystem(false);
        } else {
            RoleGrants previous = AdminGrantsSupport.parseLenient(role.getCode(), role.getGrants());
            if (role.isSystem()) {
                requested = GrantsCodec.parse(GrantConstants.ADMIN_GRANTS);
            }
            ensureCovered(caller, GrantsAuthorities.added(previous, requested));
            if (AdminGrantsSupport.hasAdminAction(previous) && !AdminGrantsSupport.hasAdminAction(requested)
                    && !support.hasOtherEnabledAdmin(null, role.getId())) {
                throw new IllegalStateException(AdminGrantsSupport.LAST_ADMIN_MESSAGE);
            }
        }
        role.setName(request.name().trim());
        role.setDescription(request.description());
        role.setGrants(GrantsCodec.write(requested));
        return toDto(roleRepository.save(role));
    }

    /**
     * Удаляет роль.
     *
     * @throws EntityNotFoundException если роли нет
     * @throws IllegalStateException   если роль системная или назначена пользователям
     */
    @Transactional
    public void delete(String code) {
        Role role = findRole(code);
        if (role.isSystem()) {
            throw new IllegalStateException("Системную роль нельзя удалить");
        }
        if (userRoleRepository.existsByIdRoleId(role.getId())) {
            throw new IllegalStateException("Роль назначена пользователям");
        }
        roleRepository.delete(role);
    }

    private static void ensureCovered(RoleGrants caller, RoleGrants wanted) {
        if (!GrantsAuthorities.covers(caller, wanted)) {
            throw new GrantsAccessDeniedException("Нельзя выдать права, которых нет у вас");
        }
    }

    /** Строгий разбор грантов из запроса; отсутствующее значение или {@code null} — без прав. */
    private static RoleGrants parseRequested(JsonNode grants) {
        if (grants == null || grants.isNull() || grants.isMissingNode()) {
            return RoleGrants.empty();
        }
        return GrantsCodec.parse(MAPPER.writeValueAsString(grants));
    }

    private Role findRole(String code) {
        return roleRepository.findByCode(code)
                .orElseThrow(() -> new EntityNotFoundException("Роль не найдена"));
    }

    private static RoleDto toDto(Role role) {
        return new RoleDto(role.getId(), role.getCode(), role.getName(), role.getDescription(), role.isSystem(),
                GrantsDto.from(AdminGrantsSupport.parseLenient(role.getCode(), role.getGrants())));
    }
}
