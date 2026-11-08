package com.argent.servicecommon.auth.service;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Создаёт системную роль {@code ADMIN} и администратора {@code argent.auth.bootstrap-admin} при запуске
 * и назначает ему эту роль.
 *
 * <p>Гранты роли {@code ADMIN} при каждом запуске приводятся к {@link GrantConstants#ADMIN_GRANTS}.
 * Пароль существующего администратора не меняется. Если пароль обязателен, а администратора
 * ещё нет и пароль не задан, запуск прерывается.</p>
 */
@Order(BootstrapAdminInitializer.ORDER)
public class BootstrapAdminInitializer implements ApplicationRunner, Ordered {

    /** Порядок среди {@link ApplicationRunner}. */
    public static final int ORDER = 300;

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final AuthProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public BootstrapAdminInitializer(AuthProperties properties, UserRepository userRepository,
            RoleRepository roleRepository, UserRoleRepository userRoleRepository, PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager) {
        this.properties = Objects.requireNonNull(properties);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.roleRepository = Objects.requireNonNull(roleRepository);
        this.userRoleRepository = Objects.requireNonNull(userRoleRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.passwordRequired()) {
            log.warn("Вход без пароля включён (argent.auth.password-required=false)");
        }
        transactionTemplate.executeWithoutResult(status -> ensureAdmin(ensureAdminRole()));
    }

    /**
     * Создаёт роль {@code ADMIN}, если её нет, и выравнивает существующую: системная,
     * гранты — {@link GrantConstants#ADMIN_GRANTS}. Имя и описание существующей роли не меняются.
     *
     * @return роль администратора
     */
    public Role ensureAdminRole() {
        Role role = roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseGet(() -> {
            Role created = new Role();
            created.setCode(GrantConstants.ADMIN_ROLE_CODE);
            created.setName(GrantConstants.ADMIN_ROLE_NAME);
            log.info("Создана роль {}", GrantConstants.ADMIN_ROLE_CODE);
            return created;
        });
        role.setSystem(true);
        role.setGrants(GrantConstants.ADMIN_GRANTS);
        return roleRepository.save(role);
    }

    /** Создаёт администратора и назначает роль в одной транзакции: без роли администратор не остаётся. */
    private void ensureAdmin(Role adminRole) {
        String login = properties.bootstrapAdmin().login().trim();
        User admin = userRepository.findByLogin(login).orElseGet(() -> createAdmin(login));
        if (properties.passwordRequired() && admin.getPasswordHash() == null) {
            log.warn("Администратор «{}» не имеет пароля: задайте его через API пользователей", login);
        }

        UserRoleId link = new UserRoleId(admin.getId(), adminRole.getId());
        if (!userRoleRepository.existsById(link)) {
            userRoleRepository.save(new UserRole(link));
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private User createAdmin(String login) {
        String password = properties.bootstrapAdmin().password();
        if (properties.passwordRequired() && password.isBlank()) {
            throw new IllegalStateException("Задайте argent.auth.bootstrap-admin.password");
        }
        User admin = new User();
        admin.setLogin(login);
        admin.setDisplayName("Администратор");
        admin.setEnabled(true);
        if (!password.isBlank()) {
            admin.setPasswordHash(passwordEncoder.encode(password));
        }
        log.info("Создан администратор {}", login);
        return userRepository.save(admin);
    }
}
