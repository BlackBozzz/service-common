package com.argent.servicecommon.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** 403 ProblemDetail при отказе цепочки фильтров; отказы {@code @PreAuthorize} обрабатывает ArgentExceptionHandler. */
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemDetailResponseWriter writer;

    public ProblemDetailAccessDeniedHandler(JsonMapper jsonMapper) {
        this.writer = new ProblemDetailResponseWriter(jsonMapper);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        writer.write(request, response, HttpStatus.FORBIDDEN, "Недостаточно прав",
                "Недостаточно прав для выполнения операции.");
    }
}
