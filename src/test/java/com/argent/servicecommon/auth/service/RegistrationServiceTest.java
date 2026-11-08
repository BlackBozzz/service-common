package com.argent.servicecommon.auth.service;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.dto.RegisterRequest;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Проверяет, что регистрация требует существования ролей из {@code argent.auth.registration.default-roles}.
 */
class RegistrationServiceTest {

    @Test
    void shouldFailWhenDefaultRoleMissing() {
        UserRepository userRepository = mock(UserRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthProperties properties = new AuthProperties(true,
                new AuthProperties.Registration(true, List.of("MISSING")),
                new AuthProperties.BootstrapAdmin("admin", ""),
                Duration.ofDays(30),
                new AuthProperties.Jwt(Path.of("unused.pem")),
                "classpath:argent-roles.json", AuthProperties.RolesFileMode.CREATE);
        when(userRepository.findByLogin("newbie")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });
        when(roleRepository.findByCode("MISSING")).thenReturn(Optional.empty());
        RegistrationService service = new RegistrationService(properties, userRepository, roleRepository,
                mock(UserRoleRepository.class), passwordEncoder, mock(TokenService.class));

        assertThatThrownBy(() -> service.register(new RegisterRequest("newbie", "Новичок", "newbie-pass")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Роль по умолчанию не найдена: MISSING");
    }
}
