package com.argent.servicecommon.dictionary.mapper.fixture.dto;
/** DTO с циклическим вложением. */
public record TestLoopBDto(Long id, TestLoopADto a) { }
