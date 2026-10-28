package com.servicehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.servicehub.JwtTestSupport;
import com.servicehub.model.User;
import com.servicehub.model.UserRole;
import com.servicehub.repository.UserRepository;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@org.junit.jupiter.api.Tag("web")
class JwtAuthenticationIntegrationTests extends JwtTestSupport {
    private static final String PASSWORD = "senha123";
    private static final String HASH = new PasswordHashService().encode(PASSWORD);
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private JwtProperties properties;
    private MockMvc mvc;
    @BeforeEach void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void logsInAllRolesAndUsesTheSignedBearerForOwnResources(UserRole role) throws Exception {
        User user = user(role);
        var response = mvc.perform(loginRequest(" JWT@EXAMPLE.COM ", PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertThat(response.getCookies()).isEmpty();
        String token = JsonPath.read(response.getContentAsString(), "$.accessToken");
        var claims = jwtService.decode(token);
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getClaimAsStringList("roles")).containsExactly(role.name());
        mvc.perform(get("/api/users/{id}", user.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":\"invalid\",\"password\":\"senha123\"}",
            "{\"email\":\"jwt@example.com\",\"password\":\"\"}", "{invalid"})
    void returns400ForInvalidLoginBodies(String body) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "Bearer ", "Bearer invalid", "Bearer a b", "Bearer a.b.c", "Token unknown"})
    void rejectsMalformedBearerHeadersWith401(String header) throws Exception {
        mvc.perform(get("/api/users").header("Authorization", header)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void rejectsTamperingUnsignedTokensAndMultipleAuthorizationHeaders() throws Exception {
        User user = user(UserRole.CLIENT);
        String token = tokenFor(user.getEmail(), PASSWORD);
        String[] parts = token.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"1\",\"roles\":[\"ADMIN\"]}".getBytes());
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes()) + "." + parts[1] + ".";
        for (String invalid : new String[]{tampered, unsigned}) {
            mvc.perform(get("/api/users").header("Authorization", "Bearer " + invalid)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredAndFutureTokensThroughTheAuthenticationFilter() throws Exception {
        User user = user(UserRole.CLIENT);
        for (long offset : new long[]{-1800, 120}) {
            var issuer = new JwtService(properties, Clock.fixed(Instant.now().plusSeconds(offset), ZoneOffset.UTC));
            String token = issuer.generateToken(new ServiceHubPrincipal(user)).getTokenValue();
            mvc.perform(get("/api/users/{id}", user.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
    }

    @Test
    void reloadsTheRoleInsteadOfKeepingOldAdministratorPrivileges() throws Exception {
        User admin = user(UserRole.ADMIN);
        String token = tokenFor(admin.getEmail(), PASSWORD);
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        admin.setRole(UserRole.CLIENT); users.saveAndFlush(admin);
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users/{id}", admin.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CLIENT"));
        assertThat(jwtService.decode(token).getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void rejectsAStillSignedTokenAfterDeactivationOrDeletion() throws Exception {
        User user = user(UserRole.CLIENT);
        String token = tokenFor(user.getEmail(), PASSWORD);
        user.setActive(false); users.saveAndFlush(user);
        mvc.perform(get("/api/users/{id}", user.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        users.delete(user); users.flush();
        mvc.perform(get("/api/users/{id}", user.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refusesBasicCookieAndQueryAuthenticationAndDoesNotSaveASession() throws Exception {
        User user = user(UserRole.CLIENT);
        String token = tokenFor(user.getEmail(), PASSWORD);
        mvc.perform(get("/api/users/{id}", user.getId()).with(httpBasic(user.getEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/{id}", user.getId()).cookie(new jakarta.servlet.http.Cookie("access_token", token)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/{id}", user.getId()).param("access_token", token))
                .andExpect(status().isUnauthorized());
        var result = mvc.perform(get("/api/users/{id}", user.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookies()).isEmpty();
    }

    @Test
    void documentsJwtLoginAndAuthorizeWithoutBasicOrCsrfAuthentication() throws Exception {
        String document = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(document, "$.components.securitySchemes.bearerAuth.scheme")).isEqualTo("bearer");
        assertThat((String) JsonPath.read(document, "$.components.securitySchemes.bearerAuth.bearerFormat")).isEqualTo("JWT");
        java.util.Map<?, ?> schemes = JsonPath.read(document, "$.components.securitySchemes");
        assertThat(schemes.containsKey("basicAuth")).isFalse();
        java.util.Map<?, ?> responses = JsonPath.read(document, "$.paths['/api/auth/login'].post.responses");
        assertThat(responses.keySet().containsAll(java.util.List.of("200", "400", "401", "403"))).isTrue();
        assertThat((java.util.List<?>) JsonPath.read(document, "$.paths['/api/users'].get.security")).hasSize(1);
        assertThat((Boolean) JsonPath.read(document, "$.components.schemas.LoginRequest.properties.password.writeOnly")).isTrue();
    }

    private User user(UserRole role) {
        User user = new User(); user.setFullName("Usuário JWT"); user.setEmail("jwt@example.com");
        user.setRole(role); user.setPasswordHash(HASH); return users.saveAndFlush(user);
    }
}
