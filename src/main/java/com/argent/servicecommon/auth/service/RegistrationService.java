package com.argent.servicecommon.auth.service;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.dto.RegisterRequest;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.exception.RegistrationDisabledException;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.error.ValidationErrorsException;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/**
 * Самостоятельная регистрация {@code POST /api/auth/register}; работает при {@code argent.auth.registration.enabled}.
 */
public class RegistrationService {

    private final AuthProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public RegistrationService(AuthProperties properties, UserRepository userRepository,
            RoleRepository roleRepository, UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.properties = Objects.requireNonNull(properties);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.roleRepository = Objects.requireNonNull(roleRepository);
        this.userRoleRepository = Objects.requireNonNull(userRoleRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.tokenService = Objects.requireNonNull(tokenService);
    }

    /**
     * Создаёт пользователя, назначает роли {@code argent.auth.registration.default-roles} и выдаёт токен —
     * одной транзакцией.
     *
     * @throws RegistrationDisabledException если регистрация выключена (403)
     * @throws ValidationErrorsException     если логин занят (400, поле {@code login})
     * @throws IllegalStateException         если роль из настроек не существует
     */
    @Transactional
    public String register(RegisterRequest request) {
        if (!properties.registration().enabled()) {
            throw new RegistrationDisabledException();
        }
        String login = request.login().trim();
        if (userRepository.findByLogin(login).isPresent()) {
            throw new ValidationErrorsException(Map.of("login", "Логин уже занят"));
        }

        User user = new User();
        user.setLogin(login);
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user = userRepository.save(user);

        for (String code : properties.registration().defaultRoles()) {
            Role role = roleRepository.findByCode(code).orElseThrow(() -> new IllegalStateException(
                    "Роль по умолчанию не найдена: " + code));
            userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        }
        return tokenService.issue(user);
    }
}
