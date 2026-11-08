package com.argent.servicecommon.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Роль пользователя.
 *
 * <p>Права роли хранятся JSON-документом {@link #grants} вида
 * {@code {"tables": {"<таблица>": ["read", "write"]}, "actions": ["<действие>"]}};
 * разбор и проверка документа выполняются в сервисах.</p>
 */
@Entity
@Table(name = "common_role")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private boolean system;

    /** Гранты роли в виде JSON; колонка {@code jsonb}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grants", nullable = false, columnDefinition = "jsonb")
    private String grants = "{}";
}
