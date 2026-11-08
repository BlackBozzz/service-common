package com.argent.servicecommon.security.dto;

import java.util.List;

/** Замена ролей пользователя; пустой {@code roleCodes} снимает все роли. */
public record UserRolesRequest(
        List<String> roleCodes
) {
}
