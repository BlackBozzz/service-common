package com.argent.servicecommon.status;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.argent.servicecommon.status.dto.CommonStatusDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * REST {@code GET /api/status/{type}/transitions?from=}: статусы, доступные текущему пользователю из {@code from}
 * (без него — при создании). Любой вошедший; неизвестный тип или статус — 404.
 */
@RestController
@RequestMapping("/api/status")
@Tag(name = "Статусы")
public class StatusController {

    private final StatusTransitionService service;

    public StatusController(StatusTransitionService service) {
        this.service = Objects.requireNonNull(service);
    }

    /** Доступные текущему пользователю переходы из статуса {@code from}. */
    @GetMapping("/{type}/transitions")
    @Operation(summary = "Получить разрешённые переходы статуса")
    @PreAuthorize("isAuthenticated()")
    public List<CommonStatusDto> transitions(@PathVariable("type") String type,
                                             @RequestParam(name = "from", required = false) String from) {
        return service.allowedFrom(type, from == null || from.isBlank() ? null : from);
    }
}
