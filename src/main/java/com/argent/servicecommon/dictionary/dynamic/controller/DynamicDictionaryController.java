package com.argent.servicecommon.dictionary.dynamic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySchema;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySummary;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicOption;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST-дополнение к существующему ArgentDictionaryController.
 *
 * <p>Добавляет только новые GET-маршруты. Создание, обновление,
 * удаление и чтение одной записи остаются в основном контроллере.</p>
 */
@RestController
@RequestMapping("/api/argent-dictionary")
@Tag(name = "Каталог данных")
public class DynamicDictionaryController {
    private final DynamicDictionaryCatalog catalog;
    private final DynamicDictionarySchemaService schemaService;
    private final DynamicDictionaryQueryService queryService;

    public DynamicDictionaryController(DynamicDictionaryCatalog catalog,
                                       DynamicDictionarySchemaService schemaService,
                                       DynamicDictionaryQueryService queryService) {
        this.catalog = catalog;
        this.schemaService = schemaService;
        this.queryService = queryService;
    }

    /** Возвращает справочники, доступные в реестре. */
    @GetMapping
    @Operation(summary = "Получить каталог таблиц")
    public List<DynamicDictionarySummary> dictionaries() {
        return catalog.list();
    }

    /** Возвращает расширенную схему формы и выбора ссылок для фронтенда. */
    @GetMapping("/{code}/schema")
    @Operation(summary = "Получить схему таблицы")
    public DynamicDictionarySchema schema(@PathVariable String code) {
        return schemaService.describe(code);
    }

    /** Возвращает страницу полноценных DTO с поиском, сортировкой и фильтрацией. */
    @GetMapping("/{code}/records")
    @Operation(summary = "Получить записи таблицы")
    public DynamicPage<?> records(
            @PathVariable String code,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam Map<String, String> parameters
    ) {
        Map<String, String> filters = new HashMap<>();
        parameters.forEach((name, value) -> {
            if (name.startsWith("filter.")) {
                filters.put(name.substring("filter.".length()), value);
            }
        });
        return queryService.page(code, page, size, sort, search, filters);
    }

    /** Возвращает компактную страницу для поля выбора существующей записи. */
    @GetMapping("/{code}/options")
    @Operation(summary = "Получить варианты выбора")
    public DynamicPage<DynamicOption> options(
            @PathVariable String code,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search
    ) {
        return queryService.options(code, page, size, search);
    }
}
