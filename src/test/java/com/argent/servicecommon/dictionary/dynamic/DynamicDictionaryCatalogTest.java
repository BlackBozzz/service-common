package com.argent.servicecommon.dictionary.dynamic;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.annotation.ArgentDataTable;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySummary;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryBadRequestException;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import com.argent.servicecommon.dictionary.dynamic.metadata.DefaultDynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.metadata.DictionaryMetadataService;
import com.argent.servicecommon.dictionary.record.DictionaryDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Проверки поиска разрешённых классов через реестр и метамодель JPA. */
class DynamicDictionaryCatalogTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final DictionaryMetadataService metadata = mock(DictionaryMetadataService.class);
    private final Metamodel metamodel = mock(Metamodel.class);
    private final DefaultDynamicDictionaryCatalog catalog =
            new DefaultDynamicDictionaryCatalog(jdbc, entityManager, metadata);

    @Table(name = "dict_country")
    @ArgentDictionary(name = "Страны", dtoClass = CountryDto.class)
    static class Country { Long id; String name; }

    record CountryDto(Long id, String name) { }

    @Table(name = "app_document")
    @ArgentDataTable(name = "Документы", group = "Управление документацией", dtoClass = DocumentDto.class)
    static class Document { Long id; String title; }

    record DocumentDto(Long id, String title) { }


    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        when(entityManager.getMetamodel()).thenReturn(metamodel);
        EntityType<Country> type = mock(EntityType.class);
        when(type.getJavaType()).thenReturn(Country.class);
        doReturn(Set.of(type)).when(metamodel).getEntities();
    }

    @Test
    void shouldResolveOnlyRegisteredJpaEntityAndItsDto() {
        when(jdbc.queryForObject(
                eq("select count(*) from common_dictionaries where code = ?"),
                eq(Integer.class), eq("dict_country"))).thenReturn(1);
        var definition = definition(CountryDto.class.getName());
        when(metadata.describe("dict_country")).thenReturn(definition);

        var result = catalog.require("dict_country");

        assertThat(result.definition()).isSameAs(definition);
        assertThat(result.entityClass()).isEqualTo(Country.class);
        assertThat(result.dtoClass()).isEqualTo(CountryDto.class);
        verify(metadata).describe("dict_country");
    }

    @Test
    void shouldRejectBlankCodeBeforeDatabaseAccess() {
        assertThatThrownBy(() -> catalog.require(" "))
                .isInstanceOf(DynamicDictionaryBadRequestException.class);
        verifyNoInteractions(jdbc, metadata);
    }

    @Test
    void shouldReturnNotFoundWhenRegistryIsEmpty() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("missing")))
                .thenReturn(0);

        assertThatThrownBy(() -> catalog.require("missing"))
                .isInstanceOf(DynamicDictionaryNotFoundException.class)
                .hasMessageContaining("missing");
        verifyNoInteractions(metadata);
    }

    @Test
    void shouldRejectInconsistentDtoMetadata() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("dict_country")))
                .thenReturn(1);
        when(metadata.describe("dict_country"))
                .thenReturn(definition(String.class.getName()));

        assertThatThrownBy(() -> catalog.require("dict_country"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DTO");
    }

    @Test
    void shouldRejectEntityMissingFromMetamodel() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("dict_country")))
                .thenReturn(1);
        when(metadata.describe("dict_country"))
                .thenReturn(definition(CountryDto.class.getName()));
        doReturn(Set.of()).when(metamodel).getEntities();

        assertThatThrownBy(() -> catalog.require("dict_country"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("отсутствует в JPA");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldListDictionarySummariesWithoutLoadingEntities() {
        var expected = List.of(new DynamicDictionarySummary("dict_country", "Страны"));
        // Метод JdbcTemplate.query() перегружен: точный перегруженный вызов
        // использует RowMapper и не требует PostgreSQL в модульном тесте.
        doReturn(expected).when(jdbc).query(
                eq("select code, name from common_dictionaries order by name, code"),
                any(RowMapper.class));

        assertThat(catalog.list()).isEqualTo(expected);
        verify(metadata, never()).describe(anyString());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldPublishDictionaryAndOwnTableWithSeparateGroups() {
        var countryType = mock(EntityType.class);
        when(countryType.getJavaType()).thenReturn(Country.class);
        var documentType = mock(EntityType.class);
        when(documentType.getJavaType()).thenReturn(Document.class);
        doReturn(Set.of(countryType, documentType)).when(metamodel).getEntities();
        doReturn(List.of(new DynamicDictionarySummary("dict_country", "Страны"))).when(jdbc).query(
                eq("select code, name from common_dictionaries order by name, code"), any(RowMapper.class));

        assertThat(catalog.list()).containsExactly(
                new DynamicDictionarySummary("dict_country", "Страны", "dictionary", "Справочники"),
                new DynamicDictionarySummary("app_document", "Документы", "table", "Управление документацией"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldResolveOwnTableWithoutRegistryOrLibraryDdl() {
        var documentType = mock(EntityType.class);
        when(documentType.getJavaType()).thenReturn(Document.class);
        doReturn(Set.of(documentType)).when(metamodel).getEntities();
        var definition = new DictionaryDefinition("app_document", "Документы",
                Document.class.getName(), DocumentDto.class.getName(), List.of());
        when(metadata.describe("app_document")).thenReturn(definition);

        var registered = catalog.require("app_document");

        assertThat(registered.entityClass()).isEqualTo(Document.class);
        assertThat(registered.dtoClass()).isEqualTo(DocumentDto.class);
        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), anyString());
    }

    private static DictionaryDefinition definition(String dtoName) {
        return new DictionaryDefinition("dict_country", "Страны",
                Country.class.getName(), dtoName, List.of());
    }
}
