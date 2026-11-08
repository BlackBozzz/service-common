package com.argent.servicecommon.security.repository;

/**
 * Код роли и её JSON-гранты: строка результата {@link RoleRepository#findGrantsByUserId}.
 *
 * @param code   код роли
 * @param grants гранты роли в виде JSON
 */
public record RoleGrantsRow(String code, String grants) {
}
