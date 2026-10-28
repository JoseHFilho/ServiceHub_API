package com.servicehub.security;

import com.servicehub.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;
    public SecurityErrorHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer realm=\"ServiceHub\"");
        write(request, response, 401, "Unauthorized", "Autenticação necessária ou credenciais inválidas.");
    }
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        write(request, response, 403, "Forbidden", "Acesso negado.");
    }
    private void write(HttpServletRequest request, HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (request.getRequestURI().equals(request.getContextPath() + "/graphql")) {
            String code = status == 401 ? "UNAUTHORIZED" : "FORBIDDEN";
            mapper.writeValue(response.getOutputStream(), Map.of("errors", List.of(Map.of(
                    "message", message, "extensions", Map.of("classification", code, "code", code)))));
            return;
        }
        mapper.writeValue(response.getOutputStream(), new ApiError(LocalDateTime.now(), status, error, message, request.getRequestURI()));
    }
}
