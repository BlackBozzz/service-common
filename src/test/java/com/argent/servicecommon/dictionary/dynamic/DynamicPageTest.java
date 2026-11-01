package com.argent.servicecommon.dictionary.dynamic;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicPage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Проверки граничных условий постраничного ответа. */
class DynamicPageTest {
    @Test void shouldCalculatePageCount() {
        var page = DynamicPage.of(List.of(1, 2), 0, 2, 5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isFalse();
    }

    @Test void shouldMarkLastPage() {
        var page = DynamicPage.of(List.of(5), 2, 2, 5);
        assertThat(page.first()).isFalse();
        assertThat(page.last()).isTrue();
    }

    @Test void shouldHandleEmptyResult() {
        var page = DynamicPage.of(List.of(), 0, 20, 0);
        assertThat(page.totalPages()).isZero();
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isTrue();
    }

    @Test void shouldPreserveLargeCounts() {
        var page = DynamicPage.of(List.of(), 0, 100, Long.MAX_VALUE);
        assertThat(page.totalPages()).isPositive();
        assertThat(page.totalElements()).isEqualTo(Long.MAX_VALUE);
    }
}
