package com.argent.servicecommon.dictionary.integration.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.integration.dto.ItDocumentDto;
import jakarta.persistence.*;

/** Проверяет SEQUENCE, два варианта Enum и составной UNIQUE. */
@Entity
@Table(name = "it_documents",
       uniqueConstraints = @UniqueConstraint(name = "uq_it_docs_country_number", columnNames = {"country_id", "number"}))
@ArgentDictionary(name = "Тестовые документы", dtoClass = ItDocumentDto.class)
public class ItDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "it_document_generator")
    @SequenceGenerator(name = "it_document_generator", sequenceName = "it_document_id_seq",
                       initialValue = 11, allocationSize = 5)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private ItCountry country;

    @Column(nullable = false, length = 30)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ItDocumentState state;

    @Enumerated(EnumType.ORDINAL)
    @Column(nullable = false)
    private ItDocumentKind kind;
}
