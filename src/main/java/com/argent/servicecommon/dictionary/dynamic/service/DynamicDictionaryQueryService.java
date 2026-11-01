package com.argent.servicecommon.dictionary.dynamic.service;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicOption;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import java.util.Map;

/** Постраничный поиск справочников и варианты выбора связанных записей. */
public interface DynamicDictionaryQueryService {
    DynamicPage<?> page(String code, int page, int size, String sort,
                        String search, Map<String, String> filters);

    DynamicPage<DynamicOption> options(String code, int page, int size, String search);
}
