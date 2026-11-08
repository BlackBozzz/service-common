package com.argent.servicecommon.security.repository;

/** Код роли и её JSON-гранты: строка результата {@link RoleRepository#findGrantsByUserId}. */
public record RoleGrantsRow(String code, String grants) {
}
