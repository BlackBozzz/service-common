package com.argent.servicecommon.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.dto.AuthConfigDto;
import com.argent.servicecommon.auth.dto.CurrentUserDto;
import com.argent.servicecommon.auth.dto.LoginRequest;
import com.argent.servicecommon.auth.dto.LoginResponse;
import com.argent.servicecommon.auth.dto.RegisterRequest;
import com.argent.servicecommon.auth.service.AuthService;
import com.argent.servicecommon.auth.service.RegistrationService;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Вход, регистрация, выход, текущий пользователь, настройки входа; {@code login}/{@code register}/{@code config}
 * открыты.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Авторизация")
public class AuthController {

    private final AuthService authService;
    private final RegistrationService registrationService;
    private final TokenService tokenService;
    private final AuthProperties properties;

    public AuthController(AuthService authService, RegistrationService registrationService,
            TokenService tokenService, AuthProperties properties) {
        this.authService = Objects.requireNonNull(authService);
        this.registrationService = Objects.requireNonNull(registrationService);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.properties = Objects.requireNonNull(properties);
    }

    /** Вход по логину (и паролю, если он требуется); 401 при ошибке. */
    @PostMapping("/login")
    @Operation(summary = "Войти в систему")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return new LoginResponse(authService.login(request));
    }

    /** Регистрация с немедленной выдачей токена; 403, если регистрация выключена, 400, если логин занят. */
    @PostMapping("/register")
    @Operation(summary = "Зарегистрировать пользователя")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        return new LoginResponse(registrationService.register(request));
    }

    /** Отзывает сессию текущего токена. */
    @PostMapping("/logout")
    @Operation(summary = "Выйти из системы")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        CurrentUser.sessionId().ifPresent(tokenService::revoke);
    }

    /** Текущий пользователь с эффективными грантами (таблицы и действия). */
    @GetMapping("/me")
    @Operation(summary = "Получить текущего пользователя")
    public CurrentUserDto me() {
        return authService.me();
    }

    /** Настройки входа для клиента. */
    @GetMapping("/config")
    @Operation(summary = "Получить настройки регистрации")
    public AuthConfigDto config() {
        return new AuthConfigDto(properties.passwordRequired(), properties.registration().enabled());
    }
}
