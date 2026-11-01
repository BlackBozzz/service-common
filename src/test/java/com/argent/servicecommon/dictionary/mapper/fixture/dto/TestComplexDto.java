package com.argent.servicecommon.dictionary.mapper.fixture.dto;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.mapper.fixture.types.TestComplexState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/**
 * DTO только для TestComplexEntity.
 *
 * <p>Названия обычных полей совпадают с полями сущности;
 * связи представлены соответствующими вложенными DTO.</p>
 */
public record TestComplexDto(
        @DictionaryField(label = "Идентификатор", readOnly = true)
        Long id,

        @DictionaryField(label = "Название")
        @NotBlank @Size(min = 2, max = 120)
        String title,

        boolean enabled,
        Boolean verified,
        byte level,
        Short shortValue,
        @Min(0) Integer count,
        long total,
        Float ratio,
        Double score,
        @DecimalMin("0.00") BigDecimal price,
        BigInteger bigCounter,
        UUID externalId,
        Character grade,
        LocalDate startDate,
        LocalTime startTime,
        LocalDateTime registeredAt,
        Instant occurredAt,
        OffsetDateTime offsetAt,
        @NotNull TestComplexState status,
        byte[] payload,

        @Valid TestCountryDto country,
        @Valid TestTagDto featuredTag,
        Set<@Valid TestTagDto> tags,
        Set<@Valid TestChildDto> children,
        List<@NotBlank String> aliases,
        @Valid TestAddressDto address
) { }
