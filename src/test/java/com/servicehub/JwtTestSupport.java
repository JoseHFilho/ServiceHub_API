package com.servicehub;

import com.servicehub.model.dto.LoginRequest;
import com.servicehub.security.JwtService;
import com.servicehub.security.ServiceHubPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A fresh signing key for the test JVM; never packaged or printed. */
public abstract class JwtTestSupport {
    private static final String TEST_SECRET;
    static {
        byte[] bytes = new byte[64]; new SecureRandom().nextBytes(bytes);
        TEST_SECRET = Base64.getEncoder().encodeToString(bytes);
    }
    @Autowired private AuthenticationManager testAuthenticationManager;
    @Autowired protected JwtService jwtService;
    @Autowired protected ObjectMapper json;

    @DynamicPropertySource
    static void jwtTestProperties(DynamicPropertyRegistry registry) {
        registry.add("servicehub.jwt.secret", () -> TEST_SECRET);
        registry.add("servicehub.jwt.ttl", () -> "15m");
        registry.add("servicehub.jwt.issuer", () -> "servicehub-api");
    }

    protected String tokenFor(String email, String password) {
        var auth = testAuthenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        return jwtService.generateToken((ServiceHubPrincipal) auth.getPrincipal()).getTokenValue();
    }
    protected RequestPostProcessor bearer(String email, String password) {
        String token = tokenFor(email, password);
        return request -> { request.addHeader("Authorization", "Bearer " + token); return request; };
    }
    protected MockHttpServletRequestBuilder loginRequest(String email, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password)));
    }
}
