package com.argent.servicecommon.dictionary.dynamic;

import com.argent.servicecommon.dictionary.dynamic.controller.DynamicDictionaryController;
import com.argent.servicecommon.dictionary.dynamic.controller.DynamicDictionaryExceptionHandler;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySchema;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySummary;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicOption;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryBadRequestException;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Проверяет фактическую передачу параметров HTTP-контроллером
 * и безопасные коды ответов библиотечного обработчика ошибок.
 */
class DynamicDictionaryControllerTest {

    private final DynamicDictionaryCatalog catalog = mock(DynamicDictionaryCatalog.class);
    private final DynamicDictionarySchemaService schemas = mock(DynamicDictionarySchemaService.class);
    private final DynamicDictionaryQueryService queries = mock(DynamicDictionaryQueryService.class);
    private final DynamicDictionaryController controller =
            new DynamicDictionaryController(catalog, schemas, queries);

    @Test
    void shouldExposeRegisteredDictionaries() {
        var expected = List.of(new DynamicDictionarySummary("dict_country", "Страны"));
        when(catalog.list()).thenReturn(expected);

        assertThat(controller.dictionaries()).isSameAs(expected);
        verify(catalog).list();
        verifyNoInteractions(schemas, queries);
    }

    @Test
    void shouldExposeFormSchema() {
        var expected = new DynamicDictionarySchema(
                "dict_country", "Страны", "id", "name", List.of());
        when(schemas.describe("dict_country")).thenReturn(expected);

        assertThat(controller.schema("dict_country")).isSameAs(expected);
        verify(schemas).describe("dict_country");
        verifyNoInteractions(catalog, queries);
    }

    @Test
    void shouldPassOnlyPrefixedFiltersToQueryService() {
        DynamicPage<String> expected = DynamicPage.of(List.of("Россия"), 0, 10, 1);

        // page() возвращает DynamicPage<?>. doReturn исключает capture-конфликт Mockito.
        doReturn(expected).when(queries).page(
                "dict_country", 0, 10, "name,asc", "Рос", Map.of("name", "Россия"));

        var actual = controller.records("dict_country", 0, 10, "name,asc", "Рос",
                Map.of("page", "0", "size", "10", "search", "Рос",
                        "filter.name", "Россия", "another", "ignored"));

        assertThat(actual).isSameAs(expected);
        verify(queries).page("dict_country", 0, 10,
                "name,asc", "Рос", Map.of("name", "Россия"));
        verifyNoInteractions(catalog, schemas);
    }

    @Test
    void shouldPassEmptyFiltersWhenNoFiltersSupplied() {
        DynamicPage<String> expected = DynamicPage.of(List.of(), 2, 20, 0);
        doReturn(expected).when(queries).page(
                "dict_country", 2, 20, null, null, Map.of());

        var actual = controller.records("dict_country", 2, 20, null, null,
                Map.of("page", "2", "sort", "id,asc"));

        assertThat(actual).isSameAs(expected);
        verify(queries).page("dict_country", 2, 20, null, null, Map.of());
    }

    @Test
    void shouldExposeReferenceOptions() {
        DynamicPage<DynamicOption> expected = DynamicPage.of(
                List.of(new DynamicOption("1", "Россия")), 0, 20, 1);
        when(queries.options("dict_country", 0, 20, "Рос")).thenReturn(expected);

        assertThat(controller.options("dict_country", 0, 20, "Рос")).isSameAs(expected);
        verify(queries).options("dict_country", 0, 20, "Рос");
    }

    @Test
    void shouldMapMissingDictionaryTo404() {
        var response = new DynamicDictionaryExceptionHandler()
                .notFound(new DynamicDictionaryNotFoundException("missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("missing");
    }

    @Test
    void shouldReturn400ForBadRequest() {
        var response = new DynamicDictionaryExceptionHandler()
                .badRequest(new DynamicDictionaryBadRequestException("Неправильный фильтр."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("Неправильный фильтр");
    }

    @Test
    void shouldNotExposeSqlDetailsOnConflict() {
        var response = new DynamicDictionaryExceptionHandler()
                .conflict(new DataIntegrityViolationException("SQL: secret constraint details"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).doesNotContain("secret constraint details");
        assertThat(response.getBody().getTitle()).isEqualTo("Конфликт данных");
    }
}
