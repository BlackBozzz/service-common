package com.argent.servicecommon.auth.config;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.controller.AuthController;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.service.AuthService;
import com.argent.servicecommon.auth.service.BootstrapAdminInitializer;
import com.argent.servicecommon.auth.service.RegistrationService;
import com.argent.servicecommon.auth.service.RoleSeeder;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.auth.service.UserProvisioningService;
import com.argent.servicecommon.security.config.SecurityAutoConfiguration;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Вход, регистрация, импорт пользователей, bootstrap-администратор; выключается {@code argent.security.enabled=false}.
 */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@ConditionalOnProperty(name = "argent.security.enabled", havingValue = "true", matchIfMissing = true)
public class AuthAutoConfiguration {

    /** Хеширование паролей BCrypt. */
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean(AuthService.class)
    public AuthService authService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            TokenService tokenService, AuthProperties properties, RoleRepository roleRepository) {
        return new AuthService(userRepository, passwordEncoder, tokenService, properties, roleRepository);
    }

    @Bean
    @ConditionalOnMissingBean(RegistrationService.class)
    public RegistrationService registrationService(AuthProperties properties, UserRepository userRepository,
            RoleRepository roleRepository, UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder, TokenService tokenService) {
        return new RegistrationService(properties, userRepository, roleRepository, userRoleRepository,
                passwordEncoder, tokenService);
    }

    @Bean
    @ConditionalOnMissingBean(UserProvisioningService.class)
    public UserProvisioningService userProvisioningService(UserRepository userRepository) {
        return new UserProvisioningService(userRepository);
    }

    /** Загрузка стартовых ролей из {@code argent.auth.roles-file} при запуске (порядок 250). */
    @Bean
    @ConditionalOnMissingBean(RoleSeeder.class)
    public RoleSeeder roleSeeder(AuthProperties properties, ResourceLoader resourceLoader,
            RoleRepository roleRepository, PlatformTransactionManager transactionManager) {
        return new RoleSeeder(properties.rolesFile(), properties.rolesFileMode(), resourceLoader, roleRepository,
                transactionManager);
    }

    /** Создание роли ADMIN и администратора при запуске (порядок 300). */
    @Bean
    @ConditionalOnMissingBean(BootstrapAdminInitializer.class)
    public BootstrapAdminInitializer bootstrapAdminInitializer(AuthProperties properties,
            UserRepository userRepository, RoleRepository roleRepository, UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager) {
        return new BootstrapAdminInitializer(properties, userRepository, roleRepository, userRoleRepository,
                passwordEncoder, transactionManager);
    }

    /** REST-точки входа для сервлетного приложения. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean(AuthController.class)
        public AuthController authController(AuthService authService, RegistrationService registrationService,
                TokenService tokenService, AuthProperties properties) {
            return new AuthController(authService, registrationService, tokenService, properties);
        }
    }
}
