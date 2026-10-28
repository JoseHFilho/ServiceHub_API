package com.servicehub.security;

import com.servicehub.exception.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 250)
public class AuthAdmissionFilter extends OncePerRequestFilter {
    private final AuthAbuseLimiter limiter;
    private final ObjectMapper json;
    public AuthAdmissionFilter(AuthAbuseLimiter limiter, ObjectMapper json) { this.limiter = limiter; this.json = json; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !RequestBudgetFilter.publicAuthentication(request); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String email = null;
        if (request instanceof CachedRequestBody cached) {
            try {
                var root = json.readTree(cached.bytes()); var candidate = root == null ? null : root.get("email");
                if (candidate != null && candidate.isString()) email = candidate.asString();
            } catch (tools.jackson.core.JacksonException ignored) { /* MVC returns the existing safe 400 contract. */ }
        }
        var operation = request.getRequestURI().endsWith("/login") ? AuthAbuseLimiter.Operation.LOGIN : AuthAbuseLimiter.Operation.REGISTRATION;
        AutoCloseable permit;
        try {
            // Forwarded headers are never parsed here. Only a separately trusted Tomcat proxy may rewrite remoteAddr.
            permit = limiter.admit(operation, request.getRemoteAddr(), email);
        } catch (AuthRateExceeded exception) {
            response.setHeader("Retry-After", Long.toString(exception.retryAfter));
            reject(response, request, 429, "Too Many Requests", "Limite temporário excedido. Tente novamente mais tarde."); return;
        } catch (SQLException exception) {
            response.setHeader("Retry-After", "1");
            reject(response, request, 503, "Service Unavailable", "Autenticação temporariamente indisponível."); return;
        }
        try { chain.doFilter(request, response); }
        finally { try { permit.close(); } catch (Exception exception) { throw new ServletException("Falha ao encerrar admissão."); } }
    }
    private void reject(HttpServletResponse response, HttpServletRequest request, int status, String title, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), new ApiError(LocalDateTime.now(), status, title, message, request.getRequestURI()));
    }
}
