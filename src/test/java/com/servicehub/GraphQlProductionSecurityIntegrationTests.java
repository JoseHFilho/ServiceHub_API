package com.servicehub;

import com.servicehub.graphql.GraphQlBodyLimitFilter;
import com.servicehub.model.*;
import com.servicehub.repository.UserRepository;
import com.servicehub.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.graphql.schema.introspection.enabled=true", "spring.graphql.graphiql.enabled=true", "spring.graphql.schema.printer.enabled=true"})
@ActiveProfiles({"test","prod"})
@Transactional
@org.junit.jupiter.api.Tag("web")
class GraphQlProductionSecurityIntegrationTests extends JwtTestSupport {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired GraphQlBodyLimitFilter bodyLimit;
    private static final String HASH = new PasswordHashService().hash("senha123");

    @Test
    void productionOverridesDevelopmentToolsEvenWhenPropertiesTryToEnableThem() throws Exception {
        var user = new User(); user.setFullName("Admin GraphQL"); user.setEmail("prod-gql@servicehub.test");
        user.setRole(UserRole.ADMIN); user.setPasswordHash(HASH); users.saveAndFlush(user);
        String token = jwtService.generateToken(new ServiceHubPrincipal(user)).getTokenValue();
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilter(bodyLimit,"/graphql").build();
        mvc.perform(get("/graphiql").header("Authorization","Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/graphql/schema").header("Authorization","Bearer " + token)).andExpect(status().isForbidden());
        var response = mvc.perform(post("/graphql").header("Authorization","Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{__schema{queryType{name}}}\"}")).andReturn();
        if (response.getRequest().isAsyncStarted()) response = mvc.perform(asyncDispatch(response)).andReturn();
        var body = json.readTree(response.getResponse().getContentAsByteArray());
        assertThat(body.path("errors").isEmpty()).isFalse();
        assertThat(body.path("errors").get(0).path("extensions").path("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(body.path("data").path("__schema").isMissingNode()).isTrue();
    }
}
