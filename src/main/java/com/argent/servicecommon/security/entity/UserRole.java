package com.argent.servicecommon.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Связь пользователя с ролью.
 */
@Entity
@Table(name = "common_user_role")
@Getter
@Setter
@NoArgsConstructor
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    public UserRole(UserRoleId id) {
        this.id = id;
    }
}
