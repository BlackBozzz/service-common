package com.argent.servicecommon.dictionary.dynamic;

import com.argent.servicecommon.dictionary.dynamic.controller.DynamicDictionaryController;
import com.argent.servicecommon.dictionary.dynamic.controller.DynamicDictionaryExceptionHandler;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySummary;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Проверяет реальные GET-маршруты Spring MVC без Spring Boot-контекста,
 * Docker и подключения к базе данных.
 */
class DynamicDictionaryWebMvcTest {

    private final DynamicDictionaryCatalog catalog = mock(DynamicDictionaryCatalog.class);
    private final DynamicDictionarySchemaService schemas = mock(DynamicDictionarySchemaService.class);
    private final DynamicDictionaryQueryService queries = mock(DynamicDictionaryQueryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new DynamicDictionaryController(catalog, schemas, queries))
                .setControllerAdvice(new DynamicDictionaryExceptionHandler())
                .build();
    }

    @Test
    void shouldReturnDictionaryListAsJson() throws Exception {
        when(catalog.list()).thenReturn(List.of(
                new DynamicDictionarySummary("dict_country", "Страны")));

        mvc.perform(get("/api/argent-dictionary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("dict_country"))
                .andExpect(jsonPath("$[0].name").value("Страны"));
    }

    @Test
    void shouldDelegatePageSortAndFilters() throws Exception {
        DynamicPage<String> expected = DynamicPage.of(List.of("Россия"), 1, 5, 8);
        doReturn(expected).when(queries).page("dict_country", 1, 5,
                "name,desc", "рос", Map.of("name", "Россия"));

        mvc.perform(get("/api/argent-dictionary/dict_country/records")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "name,desc")
                        .param("search", "рос")
                        .param("filter.name", "Россия"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(8))
                .andExpect(jsonPath("$.items[0]").value("Россия"));

        verify(queries).page("dict_country", 1, 5,
                "name,desc", "рос", Map.of("name", "Россия"));
    }

    @Test
    void shouldReturn404ForUnregisteredDictionary() throws Exception {
        when(schemas.describe("missing"))
                .thenThrow(new DynamicDictionaryNotFoundException("missing"));

        mvc.perform(get("/api/argent-dictionary/missing/schema"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldReturn400ForInvalidPageParameter() throws Exception {
        mvc.perform(get("/api/argent-dictionary/dict_country/records")
                        .param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(queries);
    }
}
