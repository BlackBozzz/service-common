package com.argent.servicecommon.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileNamesTest {

    @Test
    void shouldReplaceForbiddenCharacters() {
        assertThat(FileNames.sanitize("a/b:c", "x")).isEqualTo("a_b_c");
    }

    @Test
    void shouldFallbackOnDotsOnly() {
        assertThat(FileNames.sanitize("...", "x")).isEqualTo("x");
    }

    @Test
    void shouldFallbackOnNull() {
        assertThat(FileNames.sanitize(null, "export")).isEqualTo("export");
    }

    @Test
    void shouldTruncateTo100() {
        assertThat(FileNames.sanitize("a".repeat(120), "x")).hasSize(100);
    }
}
