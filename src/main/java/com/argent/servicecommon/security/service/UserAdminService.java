package com.argent.servicecommon.security.service;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.error.ValidationErrorsException;
import com.argent.servicecommon.security.dto.CreateUserRequest;
import com.argent.servicecommon.security.dto.SetPasswordRequest;
import com.argent.servicecommon.security.dto.UpdateUserRequest;
import com.argent.servicecommon.security.dto.UserDto;
import com.argent.servicecommon.security.dto.UserRolesRequest;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.exception.GrantsAccessDeniedException;
import com.argent.servicecommon.security.grants.GrantsAuthorities;
import com.argent.servicecommon.security.grants.GrantsCodec;
import com.argent.servicecommon.security.grants.RoleGrants;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Администрирование пользователей: список, создание, изменение, роли, пароль и сессии.
 *
 * <ul>
 *     <li>Изменять пользователя, его роли, пароль и сессии можно, только если его эффективные гранты
 *     покрываются грантами вызывающего; над собой — всегда.</li>
 *     <li>Назначить роль можно, только если её гранты покрываются грантами вызывающего
 *     (проверяются только добавляемые роли).</li>
 *     <li>Нельзя отключить себя, снять с себя действие {@code admin} и оставить систему без активного
 *     пользователя с действием {@code admin}.</li>
 * </ul>
 */
public class UserAdminService {

    /** Размер страницы по умолчанию. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Максимальный размер страницы. */
    public static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AdminGrantsSupport support;

    public UserAdminService(UserRepository userRepository, RoleRepository roleRepository,
            UserRoleRepository userRoleRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.roleRepository = Objects.requireNonNull(roleRepository);
        this.userRoleRepository = Objects.requireNonNull(userRoleRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.support = new AdminGrantsSupport(roleRepository, userRoleRepository);
    }

    /**
     * Страница пользователей, отсортированная по логину.
     *
     * @param search подстрока логина или отображаемого имени без учёта регистра; пустая — все
     * @param page   номер страницы с нуля
     * @param size   размер страницы, ограничивается диапазоном 1..{@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public DynamicPage<UserDto> list(String search, int page, int size) {
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), pageSize, Sort.by("login"));
        Page<User> users = search == null || search.isBlank()
                ? userRepository.findAll(pageable)
                : userRepository.findByLoginContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
                        search.trim(), search.trim(), pageable);
        Map<UUID, String> roleCodes = roleCodesById();
        List<UserDto> items = users.getContent().stream()
                .map(user -> toDto(user, roleCodes))
                .toList();
        return DynamicPage.of(items, pageable.getPageNumber(), pageSize, users.getTotalElements());
    }

    /**
     * Создаёт пользователя без ролей; пароль хешируется, без пароля хеш не задаётся.
     *
     * @throws ValidationErrorsException если логин занят (поле {@code login})
     */
    @Transactional
    public UserDto create(CreateUserRequest request) {
        String login = request.login().trim();
        if (userRepository.findByLogin(login).isPresent()) {
            throw new ValidationErrorsException(Map.of("login", "Логин уже занят"));
        }
        User user = new User();
        user.setLogin(login);
        user.setDisplayName(request.displayName().trim());
        user.setEnabled(true);
        if (request.password() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        return toDto(userRepository.save(user), roleCodesById());
    }

    /**
     * Изменяет отображаемое имя и активность пользователя.
     *
     * @throws GrantsAccessDeniedException если права пользователя шире прав вызывающего
     * @throws IllegalStateException       если вызывающий отключает себя или отключается последний
     *                                     активный пользователь с действием {@code admin}
     */
    @Transactional
    public UserDto update(UUID id, UpdateUserRequest request) {
        User user = findUser(id);
        ensureCanManage(id);
        if (!request.enabled() && user.isEnabled()) {
            if (isCaller(id)) {
                throw new IllegalStateException("Нельзя отключить самого себя");
            }
            if (AdminGrantsSupport.hasAdminAction(support.effectiveGrants(id))
                    && !support.hasOtherEnabledAdmin(id, null)) {
                throw new IllegalStateException(AdminGrantsSupport.LAST_ADMIN_MESSAGE);
            }
        }
        user.setDisplayName(request.displayName().trim());
        user.setEnabled(request.enabled());
        return toDto(userRepository.save(user), roleCodesById());
    }

    /**
     * Заменяет роли пользователя переданным набором кодов.
     *
     * @throws ValidationErrorsException   если роль не найдена (поле {@code roleCodes})
     * @throws GrantsAccessDeniedException если права пользователя шире прав вызывающего
     *                                     или добавляемая роль выдаёт права, которых нет у вызывающего
     * @throws IllegalStateException       если вызывающий снимает с себя действие {@code admin}
     *                                     или система остаётся без активного администратора
     */
    @Transactional
    public UserDto setRoles(UUID id, UserRolesRequest request) {
        User user = findUser(id);
        Set<String> codes = request.roleCodes() == null ? Set.of() : new LinkedHashSet<>(request.roleCodes());
        Map<UUID, Role> target = new LinkedHashMap<>();
        for (String code : codes) {
            Role role = roleRepository.findByCode(code).orElseThrow(() ->
                    new ValidationErrorsException(Map.of("roleCodes", "Неизвестная роль: " + code)));
            target.put(role.getId(), role);
        }
        ensureCanManage(id);

        List<UserRole> current = userRoleRepository.findByIdUserId(id);
        Set<UUID> currentRoleIds = current.stream()
                .map(link -> link.getId().getRoleId())
                .collect(Collectors.toSet());
        RoleGrants caller = support.callerGrants();
        List<RoleGrants> targetGrants = new ArrayList<>();
        for (Role role : target.values()) {
            if (currentRoleIds.contains(role.getId())) {
                targetGrants.add(AdminGrantsSupport.parseLenient(role.getCode(), role.getGrants()));
            } else {
                RoleGrants grants = strictGrants(role);
                if (!GrantsAuthorities.covers(caller, grants)) {
                    throw new GrantsAccessDeniedException(
                            "Нельзя выдать роль с правами, которых нет у вас: " + role.getCode());
                }
                targetGrants.add(grants);
            }
        }

        boolean losesAdmin = AdminGrantsSupport.hasAdminAction(support.effectiveGrants(id))
                && !AdminGrantsSupport.hasAdminAction(GrantsAuthorities.merge(targetGrants));
        if (losesAdmin) {
            if (isCaller(id)) {
                throw new IllegalStateException("Нельзя снять с себя действие администратора");
            }
            if (user.isEnabled() && !support.hasOtherEnabledAdmin(id, null)) {
                throw new IllegalStateException(AdminGrantsSupport.LAST_ADMIN_MESSAGE);
            }
        }

        for (UserRole link : current) {
            if (!target.containsKey(link.getId().getRoleId())) {
                userRoleRepository.delete(link);
            }
        }
        target.keySet().stream()
                .filter(roleId -> !currentRoleIds.contains(roleId))
                .map(roleId -> new UserRole(new UserRoleId(id, roleId)))
                .forEach(userRoleRepository::save);
        return toDto(user, roleCodesById());
    }

    /**
     * Устанавливает пользователю новый пароль и отзывает его действующие сессии.
     *
     * @throws GrantsAccessDeniedException если права пользователя шире прав вызывающего
     */
    @Transactional
    public void setPassword(UUID id, SetPasswordRequest request) {
        User user = findUser(id);
        ensureCanManage(id);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        tokenService.revokeAll(id);
    }

    /**
     * Отзывает все действующие сессии пользователя.
     *
     * @throws GrantsAccessDeniedException если права пользователя шире прав вызывающего
     */
    @Transactional
    public void revokeSessions(UUID id) {
        findUser(id);
        ensureCanManage(id);
        tokenService.revokeAll(id);
    }

    /** Разрешает управлять пользователем, только если его права покрываются правами вызывающего; себя — всегда. */
    private void ensureCanManage(UUID targetId) {
        if (isCaller(targetId)) {
            return;
        }
        if (!GrantsAuthorities.covers(support.callerGrants(), support.effectiveGrants(targetId))) {
            throw new GrantsAccessDeniedException("Нельзя управлять пользователем с большими правами");
        }
    }

    /**
     * Строгий разбор грантов назначаемой роли: роль с некорректными грантами в базе назначить нельзя,
     * чтобы исправление грантов позже не выдало пользователю права в обход проверки.
     */
    private static RoleGrants strictGrants(Role role) {
        try {
            return GrantsCodec.parse(role.getGrants());
        } catch (ValidationErrorsException exception) {
            throw new GrantsAccessDeniedException(
                    "Нельзя выдать роль с правами, которых нет у вас: " + role.getCode());
        }
    }

    private boolean isCaller(UUID id) {
        return support.callerId().map(id::equals).orElse(false);
    }

    private User findUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Пользователь не найден"));
    }

    private Map<UUID, String> roleCodesById() {
        return roleRepository.findAll().stream()
                .collect(Collectors.toMap(Role::getId, Role::getCode));
    }

    private UserDto toDto(User user, Map<UUID, String> roleCodes) {
        List<String> roles = userRoleRepository.findByIdUserId(user.getId()).stream()
                .map(link -> roleCodes.get(link.getId().getRoleId()))
                .sorted()
                .toList();
        return new UserDto(user.getId(), user.getLogin(), user.getDisplayName(), user.isEnabled(),
                user.getPasswordHash() != null, roles, user.getCreatedAt());
    }
}
