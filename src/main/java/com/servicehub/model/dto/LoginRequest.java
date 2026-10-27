package com.servicehub.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.servicehub.validation.InputNormalizer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "O e-mail é obrigatório.") @Email(message = "Informe um e-mail válido.")
        @Size(max = 255, message = "Informe um e-mail válido.")
        @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Informe um e-mail válido.")
        @Schema(example = "maria@servicehub.com", format = "email") String email,
        @NotBlank(message = "A senha é obrigatória.")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password
) {
    public LoginRequest { email = InputNormalizer.email(email); }
    @Override public String toString() { return "LoginRequest[credenciais protegidas]"; }
}
