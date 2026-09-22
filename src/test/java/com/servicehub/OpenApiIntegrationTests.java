package com.servicehub;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class OpenApiIntegrationTests {
    @Autowired
    private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void servesSwaggerUiAtTheRequiredAddress() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        String html = mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Swagger UI");
    }

    @Test
    void documentsEveryBusinessOperationAndPayload() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Map<String, Object>> paths = JsonPath.read(json, "$.paths");
        paths.values().forEach(item -> item.forEach((method, value) -> {
            if (java.util.Set.of("get", "post", "put", "patch", "delete").contains(method)) {
                Map<?, ?> operation = (Map<?, ?>) value;
                assertThat(operation.get("summary")).isNotNull();
                assertThat(operation.get("description")).isNotNull();
            }
        }));
        Map<String, String[]> expected = Map.of(
                "/api/users", new String[]{"get", "post"},
                "/api/users/{id}", new String[]{"get", "put", "patch", "delete"},
                "/api/hello", new String[]{"get"},
                "/api/status", new String[]{"get"});
        expected.forEach((path, methods) -> {
            assertThat(paths).containsKey(path);
            for (String method : methods) {
                @SuppressWarnings("unchecked")
                Map<String, Object> operation = (Map<String, Object>) paths.get(path).get(method);
                assertThat(operation).isNotNull();
                assertThat(operation.get("summary").toString()).isNotBlank();
                assertThat(operation.get("description").toString()).isNotBlank();
                assertThat(operation).containsKey("tags");
            }
        });
        assertThat((String) JsonPath.read(json, "$.components.schemas.UserDTO.properties.email.example"))
                .isEqualTo("maria@servicehub.com");
        assertThat((Boolean) JsonPath.read(json, "$.components.schemas.UserDTO.properties.password.writeOnly"))
                .isTrue();
        Map<String, Object> responseFields = JsonPath.read(json, "$.components.schemas.UserResponse.properties");
        assertThat(responseFields).doesNotContainKeys("password", "passwordHash");
        assertThat((Map<String, Object>) JsonPath.read(json, "$.paths['/api/users/{id}'].delete.responses"))
                .containsKeys("204", "404", "409");
    }
}
