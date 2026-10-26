package com.argent.servicecommon.dictionary.integration.dto;

/** DTO для проверки регистрации тестовой сущности. */
public record ItDocumentDto(Long id, Long countryId, String number, String state, Integer kind) {
}
