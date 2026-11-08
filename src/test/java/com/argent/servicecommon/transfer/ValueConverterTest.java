package com.argent.servicecommon.transfer;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicFieldDefinition;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicOption;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicReferenceDefinition;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;
import com.argent.servicecommon.transfer.imp.ValueConverter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Преобразование строк ячеек в типы полей DTO. */
class ValueConverterTest {

    private final ValueConverter converter = new ValueConverter(new FakeQueries(List.of(
            new DynamicOption("1", "Центральный"),
            new DynamicOption("2", "Центральный федеральный"),
            new DynamicOption("3", "Северный"),
            new DynamicOption("4", "северный"))));

    @Test
    void shouldConvertRussianAndEnglishNumbers() {
        assertThat(converter.convert(field("decimal"), BigDecimal.class, "1 234,50"))
                .isEqualTo(new BigDecimal("1234.50"));
        assertThat(converter.convert(field("decimal"), BigDecimal.class, "1 234,5"))
                .isEqualTo(new BigDecimal("1234.5"));
        assertThat(converter.convert(field("decimal"), BigDecimal.class, "1,234.50"))
                .isEqualTo(new BigDecimal("1234.50"));
        assertThat(converter.convert(field("decimal"), BigDecimal.class, "-0,5"))
                .isEqualTo(new BigDecimal("-0.5"));
        assertThat(converter.convert(field("integer"), Integer.class, "1 234")).isEqualTo(1234);
        assertThat(converter.convert(field("integer"), Integer.class, "1,234")).isEqualTo(1234);
        assertThat(converter.convert(field("integer"), Long.class, "68000000.0")).isEqualTo(68_000_000L);
        assertThat(converter.convert(field("decimal"), Double.class, "2,5")).isEqualTo(2.5d);
        assertThat(converter.convert(field("integer"), Integer.class, "  ")).isNull();
        assertThatThrownBy(() -> converter.convert(field("integer"), Integer.class, "много"))
                .hasMessage("Некорректное число: много");
        assertThatThrownBy(() -> converter.convert(field("integer"), Integer.class, "1,5"))
                .hasMessage("Ожидается целое число: 1,5");
        assertThatThrownBy(() -> converter.convert(field("integer"), Integer.class, "99999999999"))
                .hasMessage("Число вне допустимого диапазона: 99999999999");
    }

    @Test
    void shouldConvertDatesBooleansAndEnums() {
        assertThat(converter.convert(field("date"), LocalDate.class, "31.12.2025"))
                .isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(converter.convert(field("date"), LocalDate.class, "1.2.2025"))
                .isEqualTo(LocalDate.of(2025, 2, 1));
        assertThat(converter.convert(field("date"), LocalDate.class, "2025-12-31"))
                .isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(converter.convert(field("date"), LocalDate.class, "2025-12-31T00:00"))
                .isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(converter.convert(field("datetime"), LocalDateTime.class, "31.12.2025 10:15"))
                .isEqualTo(LocalDateTime.of(2025, 12, 31, 10, 15));
        assertThat(converter.convert(field("datetime"), LocalDateTime.class, "2025-12-31"))
                .isEqualTo(LocalDateTime.of(2025, 12, 31, 0, 0));
        assertThatThrownBy(() -> converter.convert(field("date"), LocalDate.class, "31.02.2025"))
                .hasMessage("Некорректная дата: 31.02.2025, ожидается дд.мм.гггг или гггг-мм-дд");
        assertThat(converter.convert(field("boolean"), Boolean.class, "Да")).isEqualTo(true);
        assertThat(converter.convert(field("boolean"), Boolean.class, "нет")).isEqualTo(false);
        assertThat(converter.convert(field("boolean"), Boolean.class, "1")).isEqualTo(true);
        assertThat(converter.convert(field("boolean"), Boolean.class, "FALSE")).isEqualTo(false);
        assertThatThrownBy(() -> converter.convert(field("boolean"), Boolean.class, "может"))
                .hasMessage("Некорректное логическое значение: может, ожидается да/нет");
        assertThat(converter.convert(field("enum"), ChronoUnit.class, "days")).isEqualTo(ChronoUnit.DAYS);
        assertThatThrownBy(() -> converter.convert(field("enum"), ChronoUnit.class, "weeks2"))
                .hasMessage("Неизвестное значение: weeks2");
    }

    @Test
    void shouldResolveReferenceByExactLabel() {
        DynamicFieldDefinition region = reference();

        assertThat(converter.convert(region, Object.class, "Центральный"))
                .isEqualTo(Map.of("id", "1", "name", "Центральный"));
        assertThatThrownBy(() -> converter.convert(region, Object.class, "Южный"))
                .hasMessage("Не найдена запись справочника imp_region: Южный");
        assertThatThrownBy(() -> converter.convert(region, Object.class, "Северный"))
                .hasMessage("Найдено несколько записей справочника imp_region: Северный");
    }

    private static DynamicFieldDefinition field(String type) {
        return new DynamicFieldDefinition("value", "Значение", "", type, "text", "", "", 0, true, false,
                false, null, null, null, null, null, null, null, List.of(), null, List.of(), List.of(),
                List.of());
    }

    private static DynamicFieldDefinition reference() {
        return new DynamicFieldDefinition("region", "Регион", "", "reference", "reference", "", "", 0, true,
                false, false, null, null, null, null, null, null, null, List.of(),
                new DynamicReferenceDefinition("imp_region", "id", "name", false),
                List.of(), List.of(), List.of());
    }

    /** Поиск вариантов как в реальном сервисе: подстрока без учёта регистра. */
    private record FakeQueries(List<DynamicOption> options) implements DynamicDictionaryQueryService {

        @Override
        public DynamicPage<?> page(String code, int page, int size, String sort, String search,
                                   Map<String, String> filters) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DynamicPage<DynamicOption> options(String code, int page, int size, String search) {
            String needle = search.toLowerCase(Locale.ROOT);
            List<DynamicOption> found = options.stream()
                    .filter(option -> option.label().toLowerCase(Locale.ROOT).contains(needle)).toList();
            return DynamicPage.of(found, page, size, found.size());
        }
    }
}
