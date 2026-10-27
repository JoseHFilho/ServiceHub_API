package com.servicehub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI serviceHubOpenAPI() {
        return new OpenAPI().addTagsItem(new Tag().name("Monitoramento").description("Disponibilidade da aplicação e do banco de dados."))
                .info(new Info()
                .title("ServiceHub API")
                .version("1.0.0")
                .description("ServiceHub, Etapa 3. Use POST /api/auth/login para obter JWT e o botão Authorize para enviar Authorization: Bearer. Autenticação stateless, sem Basic ou cookies de login. Usuários acessam seus próprios dados; ADMIN pode administrar usuários. Papéis e ativação são conferidos no banco a cada requisição.")
                .contact(new Contact().name("Projeto ServiceHub")
                        .url("https://github.com/JoseHFilho/ServiceHub_API")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("JWT assinado retornado por POST /api/auth/login.")));
    }

    @Bean
    public OpenApiCustomizer documentSecurity() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                if (path.startsWith("/api/users") && !(path.equals("/api/users") && method.name().equals("POST"))) {
                    operation.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
                }
            }));
        };
    }

    @Bean
    public OpenApiCustomizer documentHealth() {
        return api -> {
            if (api.getPaths() == null) return;
            if (api.getTags() != null) api.getTags().removeIf(tag -> "Actuator".equals(tag.getName()));
            api.getPaths().forEach((path, item) -> {
                if (path.startsWith("/actuator/health")) {
                    item.readOperations().forEach(operation -> {
                        operation.tags(List.of("Monitoramento"))
                            .summary("Consultar saúde da aplicação")
                            .description("Verifica a disponibilidade da aplicação e do banco de dados. Retorna 200 quando saudável ou 503 quando indisponível.");
                        operation.getResponses().addApiResponse("503", new ApiResponse().description("Aplicação ou banco indisponível"));
                    });
                } else if (path.equals("/actuator")) {
                    item.readOperations().forEach(operation -> operation.tags(List.of("Monitoramento"))
                            .summary("Consultar links de monitoramento")
                            .description("Lista os links dos endpoints de monitoramento habilitados na aplicação."));
                }
            });
        };
    }
}
