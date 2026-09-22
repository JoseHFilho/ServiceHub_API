package com.servicehub.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Dados completos para criar ou substituir um usuário. Todos os campos são obrigatórios.")
public class UserDTO {

    @Schema(description = "Nome completo", example = "Maria Silva", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fullName;
    @Schema(description = "E-mail único, normalizado para minúsculas e sem espaços externos.", example = "maria@servicehub.com", format = "email", maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;
    @Schema(description = "Senha obrigatória e não vazia. Armazenada como hash e nunca retornada.", example = "senha123", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
