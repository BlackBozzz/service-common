package com.argent.servicecommon.auth.service;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.dto.LoginRequest;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.security.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет, что вход по паролю выполняет проверку BCrypt и для несуществующих логинов,
 * чтобы время ответа не выдавало существование пользователя.
 */
class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        AuthProperties properties = new AuthProperties(true,
                new AuthProperties.Registration(false, List.of()),
                new AuthProperties.BootstrapAdmin("admin", ""),
                Duration.ofDays(30),
                new AuthProperties.Jwt(Path.of("unused.pem")),
                "classpath:argent-roles.json", AuthProperties.RolesFileMode.CREATE);
        authService = new AuthService(userRepository, passwordEncoder, mock(TokenService.class), properties,
                mock(RoleRepository.class));
    }

    @Test
    void shouldCheckPasswordForUnknownLogin() {
        when(userRepository.findByLogin("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "secret123")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Неверный логин или пароль");

        verify(passwordEncoder, times(1)).matches(any(), anyString());
    }

    @Test
    void shouldCheckPasswordForUserWithoutPassword() {
        User user = new User();
        user.setLogin("ivan");
        when(userRepository.findByLogin("ivan")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ivan", "secret123")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Пароль не задан, обратитесь к администратору");

        verify(passwordEncoder, times(1)).matches(any(), anyString());
    }
}
