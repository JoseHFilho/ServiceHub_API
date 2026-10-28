package com.servicehub.graphql;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds JSON variables as well as the document, including requests using chunked transfer. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class GraphQlBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 65_536;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().equals(request.getContextPath() + "/graphql");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"errors\":[{\"message\":\"Requisição GraphQL muito grande.\",\"extensions\":{\"classification\":\"BAD_REQUEST\",\"code\":\"PAYLOAD_TOO_LARGE\"}}]}");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
            @Override public ServletInputStream getInputStream() {
                var bytes = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return bytes.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return bytes.read(target, offset, length); }
                    @Override public boolean isFinished() { return bytes.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        try {
                            if (!isFinished()) listener.onDataAvailable();
                            if (isFinished()) listener.onAllDataRead();
                        } catch (IOException error) { listener.onError(error); }
                    }
                };
            }
        }, response);
    }
}
