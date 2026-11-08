package com.argent.servicecommon.status;

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
 * REST статусной машины: {@code GET /api/status/{type}/transitions?from=} — статусы, в которые текущий
 * пользователь может перевести запись из статуса {@code from}. Без {@code from} (или с пустым значением) —
 * статусы, доступные при создании записи. Доступен любому вошедшему; неизвестный тип или статус — 404.
 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final StatusTransitionService service;

    public StatusController(StatusTransitionService service) {
        this.service = Objects.requireNonNull(service);
    }

    /** Доступные текущему пользователю переходы из статуса {@code from}. */
    @GetMapping("/{type}/transitions")
    @PreAuthorize("isAuthenticated()")
    public List<CommonStatusDto> transitions(@PathVariable("type") String type,
                                             @RequestParam(name = "from", required = false) String from) {
        return service.allowedFrom(type, from == null || from.isBlank() ? null : from);
    }
}
