package com.servicehub.security;

import com.servicehub.JwtTestSupport;
import com.servicehub.model.User;
import com.servicehub.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Tag("web")
class TransportSecurityIntegrationTests extends JwtTestSupport {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired JwtProperties properties;
    MockMvc mvc;
    private static final String HASH = new PasswordHashService().hash("senha123");

    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @ParameterizedTest @ValueSource(strings = {"expired", "tampered"})
    void restAndGraphQlRejectTheSameInvalidTokenAndKeepTheNextRequestIndependent(String kind) throws Exception {
        var user = new User(); user.setFullName("Transportes"); user.setEmail("transport@servicehub.test");
        user.setPasswordHash(HASH); users.saveAndFlush(user);
        String valid = jwtService.generateToken(new ServiceHubPrincipal(user)).getTokenValue();
        String invalid;
        if (kind.equals("expired")) {
            var past = new JwtService(properties, Clock.fixed(Instant.now().minusSeconds(1800), ZoneOffset.UTC));
            invalid = past.generateToken(new ServiceHubPrincipal(user)).getTokenValue();
        } else {
            String[] parts = valid.split("\\.");
            var altered = new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("CLIENT", "ADMIN");
            invalid = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(altered.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];
            assertThat(invalid).isNotEqualTo(valid);
        }
        mvc.perform(get("/api/users/" + user.getId()).header("Authorization", "Bearer " + invalid))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/graphql").header("Authorization", "Bearer " + invalid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{services{content{id}}}\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errors[0].extensions.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/users/" + user.getId()).header("Authorization", "Bearer " + valid)).andExpect(status().isOk());
        var result = mvc.perform(post("/graphql").header("Authorization", "Bearer " + valid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{services{content{id}}}\"}")).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(result.getResponse().getContentAsByteArray()).has("errors")).isFalse();
        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
