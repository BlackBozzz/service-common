package com.argent.servicecommon.security.controller;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.security.dto.CreateUserRequest;
import com.argent.servicecommon.security.dto.SetPasswordRequest;
import com.argent.servicecommon.security.dto.UpdateUserRequest;
import com.argent.servicecommon.security.dto.UserDto;
import com.argent.servicecommon.security.dto.UserRolesRequest;
import com.argent.servicecommon.security.service.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Администрирование пользователей; доступно держателю действия {@code admin}.
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("@argentAccess.action('admin')")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = Objects.requireNonNull(userAdminService);
    }

    /** Страница пользователей с поиском по логину или отображаемому имени. */
    @GetMapping
    public DynamicPage<UserDto> list(@RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + UserAdminService.DEFAULT_PAGE_SIZE) int size) {
        return userAdminService.list(search, page, size);
    }

    /** Создаёт пользователя без ролей. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return userAdminService.create(request);
    }

    /** Изменяет отображаемое имя и активность пользователя. */
    @PutMapping("/{id}")
    public UserDto update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userAdminService.update(id, request);
    }

    /** Заменяет роли пользователя. */
    @PutMapping("/{id}/roles")
    public UserDto setRoles(@PathVariable UUID id, @Valid @RequestBody UserRolesRequest request) {
        return userAdminService.setRoles(id, request);
    }

    /** Устанавливает пользователю пароль. */
    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@PathVariable UUID id, @Valid @RequestBody SetPasswordRequest request) {
        userAdminService.setPassword(id, request);
    }

    /** Отзывает все сессии пользователя. */
    @PostMapping("/{id}/revoke-sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSessions(@PathVariable UUID id) {
        userAdminService.revokeSessions(id);
    }
}
