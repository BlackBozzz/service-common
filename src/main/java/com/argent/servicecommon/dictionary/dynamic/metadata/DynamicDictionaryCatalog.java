package com.argent.servicecommon.dictionary.dynamic.metadata;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySummary;
import java.util.List;

/** Доступ к зарегистрированным справочникам, без произвольной загрузки классов. */
public interface DynamicDictionaryCatalog {
    List<DynamicDictionarySummary> list();
    RegisteredDictionary require(String code);
}
