package com.servicehub.config;

import io.swagger.v3.oas.models.OpenAPI;
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
                .description("API acadêmica de gerenciamento de usuários. CRUD com PostgreSQL, validações e respostas de erro padronizadas. Esta etapa não implementa autenticação.")
                .contact(new Contact().name("Projeto ServiceHub")
                        .url("https://github.com/JoseHFilho/ServiceHub_API")));
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
