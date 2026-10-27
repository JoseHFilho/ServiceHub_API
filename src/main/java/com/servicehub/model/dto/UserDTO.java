package com.servicehub.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.servicehub.validation.InputNormalizer;
import com.servicehub.validation.Utf8Size;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Dados completos para criar ou substituir um usuário. Todos os campos são obrigatórios.")
public class UserDTO {

    @Schema(description = "Nome completo", example = "Maria Silva", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "O nome completo é obrigatório.")
    @Size(max = 100, message = "O nome completo deve ter no máximo 100 caracteres.")
    @Pattern(regexp = "^[^<>\\p{Cntrl}]*$", message = "O nome completo contém caracteres não permitidos.")
    private String fullName;
    @Schema(description = "E-mail único, normalizado para minúsculas e sem espaços externos.", example = "maria@servicehub.com", format = "email", maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "O e-mail é obrigatório.")
    @Email(message = "Informe um e-mail válido.")
    @Size(max = 255, message = "Informe um e-mail válido.")
    @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Informe um e-mail válido.")
    private String email;
    @Schema(description = "Senha obrigatória e não vazia. Armazenada como hash e nunca retornada.", example = "senha123", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "A senha é obrigatória.")
    @Size(min = 8, max = 72, message = "A senha deve ter entre 8 e 72 caracteres.")
    @Utf8Size
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = InputNormalizer.name(fullName);
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = InputNormalizer.email(email);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
