package com.argent.servicecommon.status.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.status.dto.CommonStatusDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Статус внутри типа (код уникален в типе); библиотечный справочник, таблица из V4. */
@Entity
@Table(
        name = "common_status",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_common_status_type_code_pair",
                columnNames = {"status_type_id", "code"}
        )
)
@ArgentDictionary(name = "Статусы", dtoClass = CommonStatusDto.class)
@Getter
@Setter
@NoArgsConstructor
public class CommonStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Тип, к которому относится статус. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_type_id", nullable = false)
    private CommonStatusType statusType;

    /** Код статуса, который приложение хранит в своей сущности. */
    @Column(nullable = false, length = 100)
    private String code;

    /** Отображаемое название статуса. */
    @Column(nullable = false)
    private String name;

    /** Порядок вывода статусов в списках. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** Признак начального статуса. */
    @Column(name = "is_initial", nullable = false)
    private Boolean initialStatus = false;

    /** Признак конечного статуса. */
    @Column(name = "is_final", nullable = false)
    private Boolean finalStatus = false;
}
