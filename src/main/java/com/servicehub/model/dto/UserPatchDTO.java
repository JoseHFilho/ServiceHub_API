package com.servicehub.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.servicehub.validation.InputNormalizer;
import com.servicehub.validation.Utf8Size;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Atualização parcial. Informe ao menos um campo não nulo. Campos omitidos ou nulos são preservados; valores em branco são rejeitados.")
public record UserPatchDTO(
        @Pattern(regexp = "(?s).*\\S.*", message = "O nome completo é obrigatório.")
        @Pattern(regexp = "^[^<>\\p{Cntrl}]*$", message = "O nome completo contém caracteres não permitidos.")
        @Size(max = 100, message = "O nome completo deve ter no máximo 100 caracteres.")
        @Schema(description = "Novo nome completo", example = "Maria Souza", maxLength = 100) String fullName,
        @Email(message = "Informe um e-mail válido.")
        @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Informe um e-mail válido.")
        @Size(max = 255, message = "Informe um e-mail válido.")
        @Schema(description = "Novo e-mail único", example = "maria.souza@servicehub.com", format = "email", maxLength = 255) String email,
        @Pattern(regexp = "(?s).*\\S.*", message = "A senha é obrigatória.")
        @Size(min = 8, max = 72, message = "A senha deve ter entre 8 e 72 caracteres.")
        @Utf8Size
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "Nova senha", example = "nova-senha123", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password
) {
    public UserPatchDTO {
        fullName = InputNormalizer.name(fullName);
        email = InputNormalizer.email(email);
    }
    @AssertTrue(message = "Informe ao menos um campo para atualizar.")
    @JsonIgnore
    public boolean isNotEmpty() { return fullName != null || email != null || password != null; }
    @Override
    public String toString() { return "UserPatchDTO[campos de atualização protegidos]"; }
}
