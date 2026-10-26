package com.argent.servicecommon.dictionary.integration.entity;

import jakarta.persistence.*;

/**
 * Дополнительная Entity без ArgentDictionary.
 * Позволяет тестировать откат ручного пакетного вызова генератора,
 * не создавая эту таблицу при обычной регистрации на старте.
 */
@Entity
@Table(name = "it_rollback_fixture")
public class ItRollbackFixture {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;
}
