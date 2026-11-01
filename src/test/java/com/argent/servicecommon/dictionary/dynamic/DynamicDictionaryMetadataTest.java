package com.argent.servicecommon.dictionary.dynamic;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicDictionaryDisplay;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicFormField;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import com.argent.servicecommon.dictionary.dynamic.metadata.DefaultDynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.RegisteredDictionary;
import com.argent.servicecommon.dictionary.record.DictionaryDefinition;
import com.argent.servicecommon.dictionary.record.DictionaryFieldDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Проверки формирования схемы динамической формы на реальном DTO-record. */
class DynamicDictionaryMetadataTest {

    private final DynamicDictionaryCatalog catalog = mock(DynamicDictionaryCatalog.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Metamodel metamodel = mock(Metamodel.class);
    private final DefaultDynamicDictionarySchemaService service =
            new DefaultDynamicDictionarySchemaService(catalog, entityManager);

    static class Country {
        Long id;
        String name;
        Integer population;
    }

    record CountryDto(
            @DictionaryField(label = "Идентификатор", readOnly = true) Long id,
            @DynamicDictionaryDisplay
            @DynamicFormField(control = DynamicFormField.Control.TEXTAREA,
                    placeholder = "Название", help = "Введите название")
            @NotBlank @Size(min = 2, max = 80) String name,
            Integer population) { }

    static class City { Long id; Country country; }
    record CityDto(Long id, CountryDto country) { }

    record ConventionalDto(Long id, String title) { }
    record DuplicateDisplay(@DynamicDictionaryDisplay String first,
                            @DynamicDictionaryDisplay String second) { }
    record InvalidDisplay(@DynamicDictionaryDisplay Long id) { }

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(entityManager.getMetamodel()).thenReturn(metamodel);

        EntityType<Country> countryType = mock(EntityType.class);
        when(metamodel.entity(Country.class)).thenReturn(countryType);
        when(metamodel.managedType(Country.class)).thenReturn(countryType);

        SingularAttribute<Country, Long> id = mock(SingularAttribute.class);
        when(id.isId()).thenReturn(true);
        when(id.getName()).thenReturn("id");
        doReturn(Set.of(id)).when(countryType).getSingularAttributes();
        when(countryType.hasSingleIdAttribute()).thenReturn(true);

        for (String name : List.of("id", "name", "population")) {
            Attribute field = mock(Attribute.class);
            when(field.getPersistentAttributeType())
                    .thenReturn(Attribute.PersistentAttributeType.BASIC);
            Class<?> javaType = switch (name) {
                case "id" -> Long.class;
                case "population" -> Integer.class;
                default -> String.class;
            };
            // getJavaType() в JPA имеет параметр Class<?>: избегаем capture wildcard.
            doReturn(javaType).when(field).getJavaType();
            doReturn(field).when(countryType).getAttribute(name);
        }

        var rules = List.of(
                new DictionaryFieldDefinition.ValidationRule("Size", Map.of("min", 2, "max", 80)),
                new DictionaryFieldDefinition.ValidationRule("NotBlank", Map.of()));

        var definition = new DictionaryDefinition("dict_country", "Страны",
                Country.class.getName(), CountryDto.class.getName(), List.of(
                field("id", "Идентификатор", "number", true, false, List.of()),
                field("name", "Страна", "string", false, true, rules),
                field("population", "Население", "number", false, false, List.of())));

        when(catalog.require("dict_country"))
                .thenReturn(new RegisteredDictionary(definition, Country.class, CountryDto.class));
    }

    private static DictionaryFieldDefinition field(String name, String label, String type,
                                                    boolean readOnly, boolean required,
                                                    List<DictionaryFieldDefinition.ValidationRule> rules) {
        return new DictionaryFieldDefinition(name, label, "", type, 0,
                readOnly, true, required, null, List.of(), List.of(), rules, List.of());
    }

    @Test
    void shouldBuildSchemaWithIdAndDisplayField() {
        var schema = service.describe("dict_country");

        assertThat(schema.code()).isEqualTo("dict_country");
        assertThat(schema.name()).isEqualTo("Страны");
        assertThat(schema.idField()).isEqualTo("id");
        assertThat(schema.displayField()).isEqualTo("name");
        assertThat(schema.fields()).hasSize(3);
    }

    @Test
    void shouldTransferUiHintsAndValidationConstraints() {
        var name = service.describe("dict_country").fields().get(1);

        assertThat(name.name()).isEqualTo("name");
        assertThat(name.label()).isEqualTo("Страна");
        assertThat(name.control()).isEqualTo("textarea");
        assertThat(name.placeholder()).isEqualTo("Название");
        assertThat(name.help()).isEqualTo("Введите название");
        assertThat(name.minLength()).isEqualTo(2);
        assertThat(name.maxLength()).isEqualTo(80);
        assertThat(name.required()).isTrue();
        assertThat(name.validation()).hasSize(2);
    }

    @Test
    void shouldKeepIdentifierReadOnlyAndInferNumberControl() {
        var schema = service.describe("dict_country");

        assertThat(schema.fields().getFirst().readOnly()).isTrue();
        assertThat(schema.fields().getFirst().type()).isEqualTo("integer");
        assertThat(schema.fields().getFirst().control()).isEqualTo("number");
        assertThat(schema.fields().get(2).type()).isEqualTo("integer");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldDescribeReferenceForSelectControl() {
        EntityType<City> cityType = mock(EntityType.class);
        when(metamodel.entity(City.class)).thenReturn(cityType);
        when(metamodel.managedType(City.class)).thenReturn(cityType);
        when(cityType.hasSingleIdAttribute()).thenReturn(true);
        SingularAttribute<City, Long> id = mock(SingularAttribute.class);
        when(id.isId()).thenReturn(true);
        when(id.getName()).thenReturn("id");
        doReturn(Set.of(id)).when(cityType).getSingularAttributes();

        Attribute countryAttribute = mock(Attribute.class);
        when(countryAttribute.getPersistentAttributeType())
                .thenReturn(Attribute.PersistentAttributeType.MANY_TO_ONE);
        doReturn(Country.class).when(countryAttribute).getJavaType();
        doReturn(countryAttribute).when(cityType).getAttribute("country");

        var definition = new DictionaryDefinition("dict_city", "Города",
                City.class.getName(), CityDto.class.getName(), List.of(
                new DictionaryFieldDefinition("country", "Страна", "", "reference",
                        0, false, true, true, "dict_country", List.of(),
                        List.of(), List.of(), List.of())));
        when(catalog.require("dict_city"))
                .thenReturn(new RegisteredDictionary(definition, City.class, CityDto.class));

        var schema = service.describe("dict_city");

        assertThat(schema.fields()).hasSize(1);
        var country = schema.fields().getFirst();
        assertThat(country.type()).isEqualTo("reference");
        assertThat(country.control()).isEqualTo("reference");
        assertThat(country.required()).isTrue();
        assertThat(country.reference()).isNotNull();
        assertThat(country.reference().dictionaryCode()).isEqualTo("dict_country");
        assertThat(country.reference().idField()).isEqualTo("id");
        assertThat(country.reference().displayField()).isEqualTo("name");
        assertThat(country.reference().multiple()).isFalse();
    }

    @Test
    void shouldSelectTitleByConvention() {
        assertThat(service.displayField(ConventionalDto.class)).isEqualTo("title");
    }

    @Test
    void shouldRejectMultipleDisplayFields() {
        assertThatThrownBy(() -> service.displayField(DuplicateDisplay.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("несколько");
    }

    @Test
    void shouldRejectNonStringDisplayField() {
        assertThatThrownBy(() -> service.displayField(InvalidDisplay.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("String");
    }

    @Test
    void shouldUseIdAsFallbackWhenNoStringDisplayField() {
        record IdOnly(Long id) { }
        assertThat(service.displayField(IdOnly.class)).isNull();
    }

    @Test
    void shouldRejectMissingRegistration() {
        when(catalog.require("missing"))
                .thenThrow(new DynamicDictionaryNotFoundException("missing"));

        assertThatThrownBy(() -> service.describe("missing"))
                .isInstanceOf(DynamicDictionaryNotFoundException.class);
    }
}
