package com.argent.servicecommon.dictionary.mapper.fixture.dto;

import java.util.Set;

/** DTO с обратной коллекционной связью, недоступной для записи. */
public record TestParentDto(Long id, Set<TestChildDto> children) { }
