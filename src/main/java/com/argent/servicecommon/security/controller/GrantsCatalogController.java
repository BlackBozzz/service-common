package com.argent.servicecommon.security.controller;

import com.argent.servicecommon.dictionary.dto.DictionaryMetadataDto;
import com.argent.servicecommon.dictionary.service.DictionaryScannerService;
import com.argent.servicecommon.security.SecurityProperties;
import com.argent.servicecommon.security.dto.GrantsCatalogDto;
import com.argent.servicecommon.security.grants.GrantConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Каталог подсказок для редактора грантов; доступен держателю действия {@code admin}.
 */
@RestController
@RequestMapping("/api/grants")
@PreAuthorize("@argentAccess.action('admin')")
public class GrantsCatalogController {

    private final DictionaryScannerService dictionaryScannerService;
    private final SecurityProperties securityProperties;

    public GrantsCatalogController(DictionaryScannerService dictionaryScannerService,
            SecurityProperties securityProperties) {
        this.dictionaryScannerService = Objects.requireNonNull(dictionaryScannerService);
        this.securityProperties = Objects.requireNonNull(securityProperties);
    }

    /**
     * Таблицы — коды зарегистрированных справочников, действия — {@code argent.security.actions}
     * и встроенное {@code admin}; оба списка по алфавиту без повторов.
     */
    @GetMapping("/catalog")
    public GrantsCatalogDto catalog() {
        List<String> tables = List.copyOf(new TreeSet<>(dictionaryScannerService.scan().stream()
                .map(DictionaryMetadataDto::code)
                .toList()));
        List<String> actions = List.copyOf(new TreeSet<>(Stream.concat(
                        securityProperties.actions().stream(), Stream.of(GrantConstants.ADMIN_ACTION, "queue.read"))
                .toList()));
        return new GrantsCatalogDto(tables, actions);
    }
}
