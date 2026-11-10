package com.argent.servicecommon.report;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFileTest {

    @Test
    void shouldEncodeCyrillicFileName() {
        var file = ReportFile.of(new byte[]{1}, ReportFormat.PDF, "Заказ ORD-1");
        var response = file.toResponseEntity();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("filename*=UTF-8''%D0%97%D0%B0%D0%BA%D0%B0%D0%B7%20ORD-1.pdf");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(1);
    }

    @Test
    void shouldSanitizeFileName() {
        var file = ReportFile.of(new byte[]{1}, ReportFormat.XLSX, "../x/y");
        assertThat(file.fileName()).endsWith(".xlsx").doesNotContain("/").doesNotContain("\\");
    }
}
