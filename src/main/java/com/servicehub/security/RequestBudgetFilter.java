package com.servicehub.security;

import com.servicehub.exception.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Runs after authentication and before JSON binding or BCrypt. GraphQL retains its own budget. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 300)
public class RequestBudgetFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final int authenticationBytes, restBytes;
    public RequestBudgetFilter(ObjectMapper json, @Value("${servicehub.request-limits.authentication-bytes:8192}") int authenticationBytes,
            @Value("${servicehub.request-limits.rest-bytes:65536}") int restBytes) {
        if (authenticationBytes < 512 || authenticationBytes > 1048576 || restBytes < 512 || restBytes > 1048576) {
            throw new IllegalStateException("Orçamento REST deve estar entre 512 bytes e 1 MiB.");
        }
        this.json = json; this.authenticationBytes = authenticationBytes; this.restBytes = restBytes;
    }
    static boolean publicAuthentication(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "POST".equals(request.getMethod()) && (path.equals("/api/auth/login") || path.equals("/api/users"));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || !(request.getMethod().equals("POST") || request.getMethod().equals("PUT") || request.getMethod().equals("PATCH"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        int maximum = publicAuthentication(request) ? authenticationBytes : restBytes;
        if (request.getContentLengthLong() > maximum) { reject(response, request); return; }
        byte[] body = request.getInputStream().readNBytes(maximum + 1);
        if (body.length > maximum) { reject(response, request); return; }
        chain.doFilter(new CachedRequestBody(request, body), response);
    }
    private void reject(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(413); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), new ApiError(LocalDateTime.now(), 413, "Payload Too Large", "Corpo da requisição excede o limite permitido.", request.getRequestURI()));
    }
}
