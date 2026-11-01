package com.argent.servicecommon.dictionary.dynamic.dto;

import java.util.List;

/** Постраничный ответ универсальной таблицы или списка выбора. */
public record DynamicPage<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        long totalPages,
        boolean first,
        boolean last
) {
    public DynamicPage {
        items = List.copyOf(items);
    }

    /** Создаёт страницу с корректными признаками начала и конца. */
    public static <T> DynamicPage<T> of(List<T> items, int page, int size, long count) {
        long pages = count == 0 ? 0 : 1 + (count - 1) / size;
        return new DynamicPage<>(items, page, size, count, pages, page == 0,
                pages == 0 || page >= pages - 1);
    }
}
