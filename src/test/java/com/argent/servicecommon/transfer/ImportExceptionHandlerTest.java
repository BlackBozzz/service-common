package com.argent.servicecommon.transfer;

import com.argent.servicecommon.transfer.imp.ImportExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/** Превышение лимита multipart Spring отвечает тем же сообщением, что и лимит импорта. */
class ImportExceptionHandlerTest {

    @Test
    void shouldMapSpringUploadLimitToBadRequest() {
        ProblemDetail detail = new ImportExceptionHandler().uploadTooLarge(new MaxUploadSizeExceededException(1024));

        assertThat(detail.getStatus()).isEqualTo(400);
        assertThat(detail.getDetail()).isEqualTo("Файл превышает допустимый размер");
    }
}
