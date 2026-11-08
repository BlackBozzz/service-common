package com.argent.servicecommon.transfer.impfixture;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Тестовый справочник с коллекцией: REPLACE для него запрещён. */
@Entity
@Table(name = "imp_with_collection")
@ArgentDictionary(name = "Справочник с коллекцией (импорт)", dtoClass = ImpWithCollectionDto.class)
@Getter
@Setter
public class ImpWithCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ElementCollection
    @CollectionTable(name = "imp_with_collection_tag", joinColumns = @JoinColumn(name = "owner_id"))
    @Column(name = "tag", nullable = false)
    private List<String> tags = new ArrayList<>();
}
