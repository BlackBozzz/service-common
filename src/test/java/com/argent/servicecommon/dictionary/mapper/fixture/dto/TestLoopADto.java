package com.argent.servicecommon.dictionary.mapper.fixture.dto;
/** DTO с циклическим вложением. */
public record TestLoopADto(Long id, TestLoopBDto b) { }
