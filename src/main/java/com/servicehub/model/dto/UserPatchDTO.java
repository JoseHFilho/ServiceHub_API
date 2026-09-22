package com.servicehub.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Atualização parcial. Informe ao menos um campo não nulo. Campos omitidos ou nulos são preservados; valores em branco são rejeitados.")
public record UserPatchDTO(
        @Schema(description = "Novo nome completo", example = "Maria Souza", maxLength = 100) String fullName,
        @Schema(description = "Novo e-mail único", example = "maria.souza@servicehub.com", format = "email", maxLength = 255) String email,
        @Schema(description = "Nova senha", example = "nova-senha123", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password
) {
}
