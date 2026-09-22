package com.servicehub;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserControllerIntegrationTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void executesTheCompleteUserCrudFlow() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Maria Silva","email":"maria@servicehub.com","password":"senha123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Maria Silva"))
                .andExpect(jsonPath("$.email").value("maria@servicehub.com"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User savedUser = userRepository.findByEmail("maria@servicehub.com").orElseThrow();
        assertThat(savedUser.getPasswordHash()).startsWith("pbkdf2$").doesNotContain("senha123");

        mockMvc.perform(get("/api/users/{id}", savedUser.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(savedUser.getId()));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("maria@servicehub.com"));

        mockMvc.perform(put("/api/users/{id}", savedUser.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Maria Souza Silva","email":"maria@servicehub.com","password":"nova-senha123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Maria Souza Silva"));

        mockMvc.perform(delete("/api/users/{id}", savedUser.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{id}", savedUser.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Usuário não encontrado: " + savedUser.getId()));
    }

    @Test
    void rejectsDuplicateEmail() throws Exception {
        String requestBody = """
                {"fullName":"Maria Silva","email":"duplicado@servicehub.com","password":"senha123"}
                """;

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("E-mail já cadastrado."));
    }

    @Test
    void rejectsInvalidUserInputWithAConsistentError() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Maria Silva","email":"email-invalido","password":"senha123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe um e-mail válido."));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A requisição contém dados inválidos."));

        mockMvc.perform(get("/api/users/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A requisição contém dados inválidos."));
    }

    @Test
    void returnsTheHelloMessageWithCorrectEncoding() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("ServiceHub API está no ar! 🚀"))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void preservesOmittedFieldsOnPatchAndRejectsIncompletePut() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Ana Silva","email":"ana.patch@example.com","password":"senha123"}
                        """))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail("ana.patch@example.com").orElseThrow();
        String originalHash = user.getPasswordHash();
        mockMvc.perform(patch("/api/users/{id}", user.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Ana Souza"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ana Souza"))
                .andExpect(jsonPath("$.email").value("ana.patch@example.com"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(originalHash);

        mockMvc.perform(put("/api/users/{id}", user.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Ana\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/users/{id}", user.getId()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/users/{id}", user.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForAllItemOperations() throws Exception {
        long absent = Long.MAX_VALUE;
        mockMvc.perform(get("/api/users/{id}", absent)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/users/{id}", absent)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/users/{id}", absent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Nome\",\"email\":\"nome@example.com\",\"password\":\"senha123\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/users/{id}", absent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Nome\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingRequiredFieldsAndUnsupportedContentType() throws Exception {
        for (String body : new String[]{"{}", "{\"fullName\":\"Maria\"}",
                "{\"fullName\":\"Maria\",\"email\":\"maria@example.com\"}"}) {
            mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.path").value("/api/users"));
        }
        mockMvc.perform(post("/api/users").contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
