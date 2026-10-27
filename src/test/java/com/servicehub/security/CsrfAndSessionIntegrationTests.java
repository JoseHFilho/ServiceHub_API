package com.servicehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.servicehub.model.User;
import com.servicehub.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

// Retains the cookie/session boundary scenarios from Stage 2 under header-only JWT.
@SpringBootTest(properties = "servicehub.test.real-csrf=true")
@ActiveProfiles("test")
@Transactional
class CsrfAndSessionIntegrationTests extends com.servicehub.JwtTestSupport {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private PasswordHashService encoder;
    private MockMvc mvc;
    @BeforeEach void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void acceptsPublicRegistrationWithoutCsrfAndNormalizesItsInput() throws Exception {
        var csrfResponse = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(csrfResponse.getCookies()).isEmpty();
        assertThat((Boolean) JsonPath.read(csrfResponse.getContentAsString(), "$.required")).isFalse();
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"  Ana   Silva  ","email":" ANA@EXAMPLE.COM ","password":"senha123",
                         "role":"ADMIN","active":false,"passwordHash":"injected"}
                        """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fullName").value("Ana Silva"))
                .andExpect(jsonPath("$.email").value("ana@example.com"))
                .andExpect(jsonPath("$.role").value("CLIENT")).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(encoder.matches("senha123", users.findByEmail("ana@example.com").orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(get("/api/users").cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "invalid")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reloadsActivationAndDoesNotAuthenticateThroughASession() throws Exception {
        User client = new User(); client.setFullName("Cliente"); client.setEmail("client@example.com");
        client.setPasswordHash(encoder.encode("senha123")); users.saveAndFlush(client);
        var authentication = bearer(client.getEmail(), "senha123");
        var request = mvc.perform(get("/api/users/{id}", client.getId()).with(authentication))
                .andExpect(status().isOk()).andReturn();
        assertThat(request.getRequest().getSession(false)).isNull();
        assertThat(request.getResponse().getCookie("JSESSIONID")).isNull();
        mvc.perform(get("/api/users/{id}", client.getId())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/{id}", client.getId()).with(authentication))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/users/{id}", client.getId()).with(authentication))
                .andExpect(status().isUnauthorized());
        assertThat(users.findById(client.getId()).orElseThrow().isActive()).isFalse();
    }
}
