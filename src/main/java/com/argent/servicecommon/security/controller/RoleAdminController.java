package com.argent.servicecommon.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.argent.servicecommon.security.dto.RoleDto;
import com.argent.servicecommon.security.dto.RoleRequest;
import com.argent.servicecommon.security.service.RoleAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Администрирование ролей; доступно держателю действия {@code admin}. Роль адресуется кодом.
 */
@RestController
@RequestMapping("/api/roles")
@PreAuthorize("@argentAccess.action('admin')")
@Tag(name = "Управление ролями")
public class RoleAdminController {

    private final RoleAdminService roleAdminService;

    public RoleAdminController(RoleAdminService roleAdminService) {
        this.roleAdminService = Objects.requireNonNull(roleAdminService);
    }

    /** Все роли с грантами. */
    @GetMapping
    @Operation(summary = "Получить роли")
    public List<RoleDto> list() {
        return roleAdminService.list();
    }

    /** Роль по коду. */
    @GetMapping("/{code}")
    @Operation(summary = "Получить роль по коду")
    public RoleDto get(@PathVariable String code) {
        return roleAdminService.get(code);
    }

    /** Создаёт роль с кодом из пути или изменяет существующую; код роли не меняется. */
    @PutMapping("/{code}")
    @Operation(summary = "Создать или изменить роль")
    public RoleDto upsert(@PathVariable String code, @Valid @RequestBody RoleRequest request) {
        return roleAdminService.upsert(code, request);
    }

    /** Удаляет несистемную роль, не назначенную пользователям. */
    @DeleteMapping("/{code}")
    @Operation(summary = "Удалить роль")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        roleAdminService.delete(code);
    }
}
