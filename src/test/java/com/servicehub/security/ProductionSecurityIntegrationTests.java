package com.servicehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.servicehub.model.User;
import com.servicehub.model.UserRole;
import com.servicehub.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles({"test", "prod"})
@Transactional
@org.junit.jupiter.api.Tag("web")
class ProductionSecurityIntegrationTests extends com.servicehub.JwtTestSupport {
    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private UserRepository users;
    @Autowired private PasswordHashService encoder;

    @Test
    void acceptsJwtAndKeepsBasicDisabledWhenProdIsActiveEvenAlongsideTest() throws Exception {
        User admin = new User(); admin.setFullName("Admin"); admin.setEmail("prod-admin@example.com");
        admin.setRole(UserRole.ADMIN); admin.setPasswordHash(encoder.encode("senha123")); users.saveAndFlush(admin);
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/users").with(httpBasic(admin.getEmail(), "senha123"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users").with(bearer(admin.getEmail(), "senha123"))).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/hello")).andExpect(status().isOk());
        assertThat(springSecurityFilterChain.getFilterChains()).allSatisfy(chain ->
                assertThat(chain.getFilters()).noneMatch(filter -> filter instanceof BasicAuthenticationFilter));
    }
}
