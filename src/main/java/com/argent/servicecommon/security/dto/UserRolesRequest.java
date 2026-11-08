package com.argent.servicecommon.security.dto;

import java.util.List;

/**
 * Запрос на замену ролей пользователя.
 *
 * @param roleCodes коды ролей; пустой список снимает все роли
 */
public record UserRolesRequest(
        List<String> roleCodes
) {
}
