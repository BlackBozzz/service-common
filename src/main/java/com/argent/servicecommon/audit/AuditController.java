package com.argent.servicecommon.audit;

import com.argent.servicecommon.audit.dto.AuditEntryDto;
import com.argent.servicecommon.audit.entity.AuditEntry;
import com.argent.servicecommon.audit.repository.AuditEntryRepository;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * REST журнала аудита {@code GET /api/audit}: по таблице — право {@code read} или действие {@code admin},
 * без {@code entityType} — только {@code admin}. Записи отдаются от новых к старым.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    /** Наибольший размер страницы. */
    public static final int MAX_PAGE_SIZE = 500;

    private static final Pattern ENTITY_TYPE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final AuditEntryRepository repository;

    public AuditController(AuditEntryRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /**
     * Ветка {@code #entityType == null} пропускает запрос без таблицы в метод, где действие {@code admin} проверяется
     * до обращения к БД: не-админ получает 400 «Укажите entityType», а не 403.
     */
    @GetMapping
    @PreAuthorize("#entityType == null or @argentAccess.table(#entityType, 'read') "
            + "or @argentAccess.action('admin')")
    public DynamicPage<AuditEntryDto> page(
            @RequestParam(name = "entityType", required = false) String entityType,
            @RequestParam(name = "entityId", required = false) String entityId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        if (entityType == null && !CurrentUser.hasAction("admin")) {
            throw new IllegalArgumentException("Укажите entityType");
        }
        if (entityType != null && !ENTITY_TYPE.matcher(entityType).matches()) {
            throw new IllegalArgumentException("Недопустимое имя таблицы: " + entityType);
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        if (from != null && to != null && from.isAfter(to)) {
            return DynamicPage.of(List.of(), safePage, safeSize, 0);
        }
        List<Specification<AuditEntry>> filters = new ArrayList<>();
        if (entityType != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("entityType"), entityType));
        }
        if (entityId != null && !entityId.isBlank()) {
            filters.add((root, query, cb) -> cb.equal(root.get("entityId"), entityId));
        }
        if (action != null && !action.isBlank()) {
            filters.add((root, query, cb) -> cb.equal(root.get("action"), action));
        }
        if (from != null) {
            filters.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
        }
        if (to != null) {
            filters.add((root, query, cb) -> cb.lessThan(root.<Instant>get("createdAt"), to));
        }
        Page<AuditEntry> result = repository.findAll(Specification.allOf(filters),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return DynamicPage.of(result.map(AuditEntryDto::from).getContent(), safePage, safeSize,
                result.getTotalElements());
    }
}
