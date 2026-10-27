package com.servicehub.model.dto;

import com.servicehub.model.User;
import com.servicehub.model.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Dados do usuário, disponíveis ao próprio titular e a ADMIN. Não inclui senha nem hash.")
public record UserResponse(
        @Schema(description = "Identificador gerado pelo banco", example = "1") Long id,
        @Schema(description = "Nome completo", example = "Maria Silva") String fullName,
        @Schema(description = "E-mail normalizado", example = "maria@servicehub.com") String email,
        @Schema(description = "Data e hora de criação", example = "2026-09-22T10:00:00") LocalDateTime createdAt,
        @Schema(description = "Papel do usuário, não editável por este DTO", example = "CLIENT") UserRole role,
        @Schema(description = "Usuário ativo", example = "true") boolean active
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getCreatedAt(),
                user.getRole(),
                user.isActive()
        );
    }
}
