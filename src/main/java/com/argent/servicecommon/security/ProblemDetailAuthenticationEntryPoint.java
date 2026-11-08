package com.argent.servicecommon.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** 401 ProblemDetail без раскрытия причины: истёкший, поддельный и отозванный токен дают одинаковый ответ. */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemDetailResponseWriter writer;

    public ProblemDetailAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.writer = new ProblemDetailResponseWriter(jsonMapper);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writer.write(request, response, HttpStatus.UNAUTHORIZED, "Требуется вход", "Требуется аутентификация.");
    }
}
