package com.servicehub.model.dto;

import com.servicehub.model.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Dados públicos do usuário. Não inclui senha nem hash.")
public record UserResponse(
        @Schema(description = "Identificador gerado pelo banco", example = "1") Long id,
        @Schema(description = "Nome completo", example = "Maria Silva") String fullName,
        @Schema(description = "E-mail normalizado", example = "maria@servicehub.com") String email,
        @Schema(description = "Data e hora de criação", example = "2026-09-22T10:00:00") LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getCreatedAt()
        );
    }
}
