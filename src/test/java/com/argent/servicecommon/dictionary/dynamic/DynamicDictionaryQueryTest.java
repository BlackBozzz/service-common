package com.argent.servicecommon.dictionary.dynamic;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySchema;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicFieldDefinition;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryBadRequestException;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.metadata.RegisteredDictionary;
import com.argent.servicecommon.dictionary.dynamic.service.DefaultDynamicDictionaryQueryService;
import com.argent.servicecommon.dictionary.mapper.DictionaryDtoMapper;
import com.argent.servicecommon.dictionary.record.DictionaryDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Проверяет отбрасывание опасных параметров до SQL,
 * а также реальные цепочки вызовов Criteria API и DTO-маппера.
 * База данных для этих проверок не требуется.
 */
class DynamicDictionaryQueryTest {

    private final DynamicDictionaryCatalog catalog = mock(DynamicDictionaryCatalog.class);
    private final DynamicDictionarySchemaService schemaService = mock(DynamicDictionarySchemaService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final DictionaryDtoMapper mapper = mock(DictionaryDtoMapper.class);
    private final DefaultDynamicDictionaryQueryService service =
            new DefaultDynamicDictionaryQueryService(catalog, schemaService, entityManager, mapper);

    static class Country {
        Long id;
        String name;
    }

    record CountryDto(Long id, String name) { }

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(catalog.require("dict_country")).thenReturn(new RegisteredDictionary(
                new DictionaryDefinition("dict_country", "Страны",
                        Country.class.getName(), CountryDto.class.getName(), List.of()),
                Country.class, CountryDto.class));

        Metamodel metamodel = mock(Metamodel.class);
        when(entityManager.getMetamodel()).thenReturn(metamodel);
        EntityType<Country> entityType = mock(EntityType.class);
        when(metamodel.managedType(Country.class)).thenReturn(entityType);
        when(metamodel.entity(Country.class)).thenReturn(entityType);

        SingularAttribute<Country, Long> id = mock(SingularAttribute.class);
        when(id.isId()).thenReturn(true);
        when(id.getName()).thenReturn("id");
        doReturn(Set.of(id)).when(entityType).getSingularAttributes();
        when(entityType.hasSingleIdAttribute()).thenReturn(true);

        for (String name : List.of("id", "name")) {
            Attribute attribute = mock(Attribute.class);
            when(attribute.getPersistentAttributeType())
                    .thenReturn(Attribute.PersistentAttributeType.BASIC);
            Class<?> javaType = name.equals("id") ? Long.class : String.class;
            // Строгая параметризация getJavaType() конфликтует с thenReturn(Class<?>).
            doReturn(javaType).when(attribute).getJavaType();
            doReturn(attribute).when(entityType).getAttribute(name);
        }
    }

    @Test
    void shouldRejectNegativePage() {
        assertThatThrownBy(() -> service.page("dict_country", -1, 20, null, null, Map.of()))
                .isInstanceOf(DynamicDictionaryBadRequestException.class);
        verify(entityManager, never()).getCriteriaBuilder();
    }

    @Test
    void shouldRejectZeroSize() {
        assertThatThrownBy(() -> service.page("dict_country", 0, 0, null, null, Map.of()))
                .isInstanceOf(DynamicDictionaryBadRequestException.class);
    }

    @Test
    void shouldRejectOversizedPage() {
        assertThatThrownBy(() -> service.page("dict_country", 0, 101, null, null, Map.of()))
                .isInstanceOf(DynamicDictionaryBadRequestException.class);
    }

    @Test
    void shouldRejectOverflowingOffset() {
        assertThatThrownBy(() -> service.page("dict_country", Integer.MAX_VALUE, 100,
                null, null, Map.of()))
                .isInstanceOf(DynamicDictionaryBadRequestException.class);
    }

    @Test
    void shouldRejectUnsafeSortField() {
        assertThatThrownBy(() -> service.page("dict_country", 0, 20,
                "name;drop table,asc", null, Map.of()))
                .isInstanceOf(DynamicDictionaryBadRequestException.class)
                .hasMessageContaining("Сортировка");
        verify(entityManager, never()).getCriteriaBuilder();
    }

    @Test
    void shouldRejectUnknownFilter() {
        assertThatThrownBy(() -> service.page("dict_country", 0, 20,
                null, null, Map.of("country.name", "X")))
                .isInstanceOf(DynamicDictionaryBadRequestException.class)
                .hasMessageContaining("Фильтрация");
    }

    @Test
    void shouldRejectInvalidNumericFilter() {
        assertThatThrownBy(() -> service.page("dict_country", 0, 20,
                null, null, Map.of("id", "not-a-number")))
                .isInstanceOf(DynamicDictionaryBadRequestException.class)
                .hasMessageContaining("Некорректное");
    }

    @Test
    void shouldRejectInvalidSortDirection() {
        assertThatThrownBy(() -> service.page("dict_country", 0, 20,
                "name,sideways", null, Map.of()))
                .isInstanceOf(DynamicDictionaryBadRequestException.class)
                .hasMessageContaining("asc или desc");
    }

    @Test
    void shouldRejectLongSearch() {
        assertThatThrownBy(() -> service.page("dict_country", 0, 20,
                null, "x".repeat(201), Map.of()))
                .isInstanceOf(DynamicDictionaryBadRequestException.class)
                .hasMessageContaining("200");
    }

    @Test
    void shouldReturnPageOfMappedDtosAndTotalCount() {
        prepareSuccessfulCriteria(List.of(country(1L, "Россия"), country(2L, "Беларусь")), 5L);
        when(mapper.toDto(any(Country.class), eq(CountryDto.class)))
                .thenAnswer(call -> {
                    Country country = call.getArgument(0);
                    return new CountryDto(country.id, country.name);
                });

        var result = service.page("dict_country", 1, 2, null, null, Map.of());

        assertThat(result.items()).isEqualTo(List.of(
                new CountryDto(1L, "Россия"), new CountryDto(2L, "Беларусь")));
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isFalse();
        verify(mapper, times(2)).toDto(any(Country.class), eq(CountryDto.class));
    }

    @Test
    void shouldSupportNamedFilterAndDescendingSort() {
        var criteria = prepareSuccessfulCriteria(List.of(country(7L, "Россия")), 1L);
        when(mapper.toDto(any(Country.class), eq(CountryDto.class)))
                .thenReturn(new CountryDto(7L, "Россия"));

        var page = service.page("dict_country", 0, 10, "name,desc", null,
                Map.of("id", "7"));

        assertThat(page.items()).isEqualTo(List.of(new CountryDto(7L, "Россия")));
        // WHERE создаётся дважды: для страницы и для COUNT.
        verify(criteria.builder, times(2)).equal(any(), eq(7L));
        verify(criteria.builder).desc(any());
        // При сортировке по неключевому полю добавляется сортировка по ID.
        verify(criteria.builder).asc(any());
    }

    @Test
    void shouldEscapePercentAndUnderscoreInSearch() {
        var criteria = prepareSuccessfulCriteria(List.of(), 0L);

        var result = service.page("dict_country", 0, 10, null, "A%_B", Map.of());

        assertThat(result.items()).isEmpty();
        verify(criteria.builder, times(2))
                .like(any(), eq("%a\\%\\_b%"), eq('\\'));
    }

    @Test
    void shouldBuildReferenceOptionsFromFullDtos() {
        prepareSuccessfulCriteria(List.of(country(9L, "Россия")), 1L);
        when(mapper.toDto(any(Country.class), eq(CountryDto.class)))
                .thenReturn(new CountryDto(9L, "Россия"));
        when(schemaService.describe("dict_country"))
                .thenReturn(new DynamicDictionarySchema("dict_country", "Страны",
                        "id", "name", List.of(field("name", true))));

        var result = service.options("dict_country", 0, 20, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().id()).isEqualTo("9");
        assertThat(result.items().getFirst().label()).isEqualTo("Россия");
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void shouldUseIdentifierWhenDisplayFieldIsUnavailable() {
        prepareSuccessfulCriteria(List.of(country(9L, null)), 1L);
        when(mapper.toDto(any(Country.class), eq(CountryDto.class)))
                .thenReturn(new CountryDto(9L, null));
        when(schemaService.describe("dict_country"))
                .thenReturn(new DynamicDictionarySchema("dict_country", "Страны",
                        "id", null, List.of()));

        var result = service.options("dict_country", 0, 20, " ");

        assertThat(result.items().getFirst().id()).isEqualTo("9");
        assertThat(result.items().getFirst().label()).isEqualTo("9");
    }

    private static Country country(Long id, String name) {
        Country value = new Country();
        value.id = id;
        value.name = name;
        return value;
    }

    private static DynamicFieldDefinition field(String name, boolean visible) {
        return new DynamicFieldDefinition(name, name, "", "string", "text", "", "",
                0, visible, false, false, null, null, null, null,
                null, null, null, List.of(), null, List.of(), List.of(), List.of());
    }

    /**
     * Создаёт только необходимые заглушки Criteria API; запрос остаётся
     * настоящим методом сервиса, а не замоканным ответом самого сервиса.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private CriteriaFixture prepareSuccessfulCriteria(List<Country> entities, long total) {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(entityManager.getCriteriaBuilder()).thenReturn(cb);

        CriteriaQuery<Country> select = mock(CriteriaQuery.class);
        CriteriaQuery<Long> count = mock(CriteriaQuery.class);
        Root<Country> selectRoot = mock(Root.class);
        Root<Country> countRoot = mock(Root.class);
        Path<Object> selectPath = mock(Path.class);
        Path<Object> countPath = mock(Path.class);
        Order ordering = mock(Order.class);

        when(cb.createQuery(Country.class)).thenReturn(select);
        when(cb.createQuery(Long.class)).thenReturn(count);
        when(select.from(Country.class)).thenReturn(selectRoot);
        when(count.from(Country.class)).thenReturn(countRoot);

        // root.get(String) типизирован по фактическому типу свойства.
        // doReturn не приводит к захвату несовместимого wildcard.
        doReturn(selectPath).when(selectRoot).get(anyString());
        doReturn(countPath).when(countRoot).get(anyString());
        when(cb.asc(any())).thenReturn(ordering);
        when(cb.desc(any())).thenReturn(ordering);

        TypedQuery<Country> selectQuery = mock(TypedQuery.class);
        TypedQuery<Long> countQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(select)).thenReturn(selectQuery);
        when(entityManager.createQuery(count)).thenReturn(countQuery);
        when(selectQuery.setFirstResult(anyInt())).thenReturn(selectQuery);
        when(selectQuery.setMaxResults(anyInt())).thenReturn(selectQuery);
        when(selectQuery.getResultList()).thenReturn(entities);
        when(countQuery.getSingleResult()).thenReturn(total);

        return new CriteriaFixture(cb);
    }

    private record CriteriaFixture(CriteriaBuilder builder) { }
}
