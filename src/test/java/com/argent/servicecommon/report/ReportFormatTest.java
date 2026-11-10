package com.argent.servicecommon.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Разбор формата отчёта, расширения и типы содержимого. */
class ReportFormatTest {

    @Test
    void parsesCaseInsensitively() {
        assertThat(ReportFormat.parse("Pdf")).isEqualTo(ReportFormat.PDF);
    }

    @Test
    void rejectsUnknownFormat() {
        assertThatThrownBy(() -> ReportFormat.parse("html")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("html").hasMessageContaining("pdf, xlsx, docx");
    }

    @Test
    void mediaTypesAndExtensions() {
        assertThat(ReportFormat.XLSX.extension()).isEqualTo("xlsx");
        assertThat(ReportFormat.DOCX.mediaType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }
}
